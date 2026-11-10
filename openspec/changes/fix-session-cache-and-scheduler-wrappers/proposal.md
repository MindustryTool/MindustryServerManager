## Why

`SessionRepository.cache` is an unbounded `ConcurrentHashMap<String, SessionData>` that never evicts entries. Every unique player who ever joins creates a `SessionData` (with its own `ArrayList<String>` and `HashMap<String, Integer>`) that stays in heap forever. On long-running servers this grows linearly with total unique players and never shrinks, increasing GC pressure and risking OOM.

The `Scheduler` creates new `Runnable` wrapper objects (and anonymous `Timer.Task` instances for main-thread reschedules) on every scheduled tick. With 20+ scheduled methods firing at 1-60 second intervals, this is constant short-lived allocation churn that stresses the young generation GC.

## What Changes

- **SessionRepository**: Replace the plain `ConcurrentHashMap` cache with a bounded, evicting cache (Caffeine or equivalent) with TTL and/or max-size eviction. Evicted entries that are dirty are flushed to the database before removal. The `dirty` set is also cleared for evicted entries.
- **Scheduler**: Reuse `Runnable` wrapper instances instead of allocating new ones on every schedule tick. Main-thread `Timer.Task` rescheduling reuses the same task instance rather than creating a new anonymous class per reschedule.

## Capabilities

### Modified Capabilities

- `session-service`: Add a bounded cache eviction requirement to `SessionRepository`. Sessions that exceed TTL or max size are evicted, with dirty data flushed to the database first. This is a spec-level behavior change — sessions are no longer retained forever.

### New Capabilities

None. Scheduler wrapper reuse is an implementation detail — observable behavior is unchanged.

## Impact

- `plugin.session.SessionRepository`: cache type, eviction logic, flush-on-evict path
- `plugin.session.SessionService`: no API change, but session objects may be evicted from the repository cache independently (active sessions in `SessionService.data` are still strongly referenced and won't be evicted)
- `plugin.core.Scheduler`: internal `Runnable`/`Timer.Task` allocation pattern, no public API change
- Tests may need updates for cache eviction behavior and wrapper reuse
