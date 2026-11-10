## 1. SessionRepository Cache Eviction

- [ ] 1.1 Replace `ConcurrentHashMap<String, SessionData> cache` with Caffeine `Cache<String, SessionData>` in `SessionRepository`
- [ ] 1.2 Configure Caffeine with `expireAfterWrite(30, MINUTES)` and `maximumSize(10000)`
- [ ] 1.3 Add Caffeine removal listener that checks the `dirty` set and flushes evicted entries via `Tasks.io`
- [ ] 1.4 Ensure `dirty` set entry is removed after successful flush-on-evict
- [ ] 1.5 Update `flushBatch()` to work with Caffeine cache API (iterate keys, check dirty)
- [ ] 1.6 Update `destroy()` to flush all remaining dirty entries before cache clear
- [ ] 1.7 Verify `get(String uuid)` still returns cached `SessionData` or loads from DB as before
- [ ] 1.8 Verify `remove(String uuid)` still works with Caffeine cache
- [ ] 1.9 Add unit test for cache eviction under maximumSize pressure
- [ ] 1.10 Add unit test for dirty flush-on-evict behavior

## 2. Scheduler Wrapper Reuse

- [ ] 2.1 Cache `Runnable` wrapper instances per `@Schedule` method in `Scheduler` instead of allocating new ones per tick
- [ ] 2.2 Reuse the same `Timer.Task` instance for main-thread reschedule instead of creating anonymous class per reschedule
- [ ] 2.3 Verify `createWrapper` timing-log wrapper is reused (not re-allocated per execution)
- [ ] 2.4 Verify `createInvocationTask` method-invocation wrapper is reused
- [ ] 2.5 Add unit test confirming no new `Runnable` allocation on repeated schedule ticks
- [ ] 2.6 Add unit test confirming main-thread `Timer.Task` self-reschedule reuses the same instance

## 3. Verification

- [ ] 3.1 Run existing test suite to ensure no regressions
- [ ] 3.2 Verify build compiles with Caffeine cache changes in SessionRepository
- [ ] 3.3 Verify build compiles with Scheduler wrapper reuse changes
