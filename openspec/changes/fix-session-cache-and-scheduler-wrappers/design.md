## Context

`SessionRepository.cache` (plugin/session/SessionRepository.java:28) is a plain `ConcurrentHashMap<String, SessionData>` with no eviction. Every unique player who ever joins creates a `SessionData` containing `ArrayList<String> unlockedTrails` and `HashMap<String, Integer> dailyTaskProgress`. These entries persist forever unless explicitly removed via `SessionRemovedEvent` or `@Destroy`. On long-running servers this grows linearly with total unique players.

`Scheduler` (plugin/core/Scheduler.java:118-143) allocates new `Runnable` wrapper objects on every `@Schedule` registration and new anonymous `Timer.Task` instances on every main-thread reschedule. With 20+ scheduled methods at 1-60 second intervals, this is constant Gen-0 allocation churn.

Caffeine is already a project dependency (used in `ApiGateway.serverQueryCache`).

## Goals / Non-Goals

**Goals:**
- Bound `SessionRepository.cache` growth so it cannot grow forever with unique players
- Flush dirty session data to the database before eviction (no data loss)
- Reduce per-tick `Runnable`/`Timer.Task` allocation in `Scheduler` to near zero
- No public API changes to `SessionRepository`, `SessionService`, or `Scheduler`

**Non-Goals:**
- Changing `SessionService.data` (active sessions are strongly referenced and unaffected by repository cache eviction)
- Changing `SessionData` structure or fields
- Changing `@Schedule` annotation semantics or validation rules
- Implementing LRU eviction if TTL-based eviction is sufficient

## Decisions

### D1: Use Caffeine for SessionRepository.cache

**Decision:** Replace `ConcurrentHashMap<String, SessionData>` with Caffeine `Cache<String, SessionData>` using `expireAfterWrite` (TTL) and `maximumSize`.

**Rationale:** Caffeine is already a project dependency with proven production use in `ApiGateway`. It provides bounded size, TTL-based expiration, and eviction listeners — exactly what we need. No new dependency required.

**Alternatives considered:**
- **Manual scheduled cleanup**: Would require custom eviction logic, TTL tracking, and concurrency handling. More code, more bug surface.
- **LinkedHashMap-based LRU**: Less feature-rich than Caffeine, no built-in eviction listener for dirty flush.
- **Guava Cache**: Not currently a dependency; would add a new transitive dependency.

### D2: Eviction listener flushes dirty entries before removal

**Decision:** Register a Caffeine removal listener that checks the `dirty` set and writes evicted entries to the database via `Tasks.io` before the entry is actually removed.

**Rationale:** Prevents data loss when sessions are evicted. The write is asynchronous (Tasks.io) so eviction doesn't block.

**Alternatives considered:**
- **Synchronous write before eviction**: Would block eviction on DB latency, causing stalls.
- **Skip flush on eviction**: Risks losing unsaved session data (exp, playTime, etc.) for evicted players who never return.

### D3: Reuse Runnable wrapper instances in Scheduler

**Decision:** Cache `Runnable` wrappers per `@Schedule` method so the same instance is reused on each tick. For main-thread `Timer.Task` rescheduling, reuse the same `Timer.Task` instance that reschedules itself rather than allocating a new anonymous class each time.

**Rationale:** The wrapper's only job is to invoke the method and measure timing. It has no per-tick state. Reusing it eliminates per-tick allocation entirely.

**Alternatives considered:**
- **Object pool of Runnables**: Adds complexity (pool management, borrow/return) for marginal benefit over per-method caching.
- **No wrapper (direct method reference)**: Would lose timing/warn logging, which is a useful diagnostic.

### D4: TTL and maxSize values

**Decision:** `expireAfterWrite(30, MINUTES)` for idle sessions, `maximumSize(10000)` as a hard cap.

**Rationale:** 30 minutes covers most active play sessions. 10,000 entries is a generous upper bound that prevents unbounded growth while allowing large servers. Active sessions in `SessionService.data` are separate and never evicted from the repository cache while the player is online — the repository cache is a read-through cache backed by the database.

**Open question:** Should TTL be `expireAfterWrite` (last write) or `expireAfterAccess` (last read)? `expireAfterWrite` is simpler and sufficient since `SessionService` holds the authoritative active-session reference; the repository cache is a secondary read-through cache.

## Risks / Trade-offs

| Risk | Mitigation |
|------|------------|
| Evicting a session that is still active in `SessionService.data` | Repository cache and `SessionService.data` are separate. Active sessions are held by `SessionService` and never removed from the repository cache while online. Eviction only affects idle cached data. |
| Dirty flush on eviction fails silently | Caffeine removal listener runs `Tasks.io` which has its own error handling. Log errors but don't throw from the listener. |
| Caffeine eviction listener threading | Caffeine listeners run on the calling thread or an executor. Use `Executor` if needed to avoid blocking the eviction path. |
| Scheduler wrapper reuse breaks timing diagnostics | The wrapper still measures elapsed time on each invocation. Reuse doesn't affect timing accuracy. |
| Main-thread Timer.Task self-reschedule reuse | The same `Timer.Task` instance calls `Timer.schedule(this, ...)` on itself. This is safe because Arc's Timer.schedule accepts the same task instance repeatedly. |

## Migration Plan

1. Deploy the Caffeine-backed `SessionRepository.cache` with the eviction listener.
2. Existing cached sessions remain valid (no data migration needed — they're read-through from the database anyway).
3. Deploy `Scheduler` wrapper reuse.
4. Monitor heap usage and GC pauses after deployment.

## Open Questions

- Should TTL be configurable via `Cfg` or hardcoded?
- Should `maximumSize` be configurable?
- What should the removal listener do if `Tasks.io` is shut down during eviction?
