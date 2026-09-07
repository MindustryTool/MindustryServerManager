## Why

During high-volume exp events (such as Tower Defense waves where turrets rapidly destroy enemy units, or multi-player rewards), exp accumulation currently synchronizes on the `SessionData` instance (`synchronized (data)`). This causes severe thread contention and server hitching, especially when background persistence tasks hold monitor locks on `SessionData` while performing JSON serialization, and when `updateLevel` unnecessarily re-broadcasts player name changes across the network on every single exp event.

## What Changes

- Replace coarse-grained monitor locking (`synchronized (data)`) on exp mutations with non-blocking, thread-safe atomic addition.
- Optimize `SessionRepository.write()` to perform JSON serialization outside the synchronization lock or on an immutable snapshot, preventing database flush operations from blocking game loop threads.
- Guard player name updates in `updateLevel` so that network broadcasts and name recalculations only occur when a player's level actually changes, rather than on every exp increment.
- Ensure dirty marking and level checking are lightweight and free of redundant operations during high-frequency exp bursts.

## Capabilities

### New Capabilities
None.

### Modified Capabilities
- `exp-system`: Require non-blocking, thread-safe exp accumulation and ensure player name formatting/broadcasting occurs only upon actual level transitions.
- `session-service`: Specify non-blocking exp processing in `onExpGain` and optimize `updateLevel` to avoid redundant player name re-syncs when the level has not changed.

## Impact

- `plugin.session.SessionService`: Remove `synchronized (data)` block in `onExpGain`; restrict `session.player.name(...)` updates to actual level changes.
- `plugin.session.SessionData`: Introduce thread-safe, lock-free exp update semantics or atomic accumulation.
- `plugin.session.SessionRepository`: Isolate `JsonUtils.toJsonString(pdata)` from the critical section in `write()`.
- Server performance: Eliminates tick lag and freeze spikes during intense combat and high-rate exp reward bursts.
