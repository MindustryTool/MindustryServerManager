## Context

In the current Mindustry server plugin implementation, exp rewards can arrive at very high frequencies—especially in gamemodes like Tower Defense where automated turrets destroy dozens of enemy units per second, or during global reward broadcasts.

When an `ExpGainEvent` is fired:
1. `SessionService.onExpGain` acquires a monitor lock on the player's `SessionData` (`synchronized (data)`).
2. Concurrently, `SessionRepository.flushBatch()` runs every 10 seconds in a background thread and calls `write(uuid, pdata)`, which acquires the same monitor lock (`synchronized (pdata)`) and holds it while performing reflection-heavy Jackson JSON serialization (`JsonUtils.toJsonString(pdata)`).
3. Any thread firing exp events (often the main Mindustry game loop during bullet collision / unit destroy handling) blocks until JSON serialization completes, creating severe tick stalls and micro-freezes.
4. `SessionService.updateLevel` invokes `session.player.name(getPlayerName.apply(session))` on *every single exp gain*, even when the player's level has not changed. This triggers redundant name formatting and sends network sync packets to all connected clients repeatedly, consuming CPU and bandwidth.
5. `sessionRepository.markDirty(session)` is called redundantly twice for every single exp event.

## Goals / Non-Goals

**Goals:**
- Eliminate monitor lock contention (`synchronized (data)`) on exp addition using atomic, lock-free operations.
- Isolate JSON serialization in `SessionRepository.write()` from any mutex protecting runtime mutations, preventing background flushes from stalling exp processing or the main game thread.
- Restrict `session.player.name(...)` updates and network broadcasts strictly to actual level transitions (`level != session.currentLevel`) or session initialization.
- Deduplicate dirty marking and streamline the exp addition path so high-frequency events execute with minimal overhead.

**Non-Goals:**
- Changing the exp formula, level calculation curves, or bonus multiplier algorithms.
- Changing database schema or persistence storage format for sessions.
- Replacing the event-driven architecture (`ExpGainEvent`) with a separate RPC or external queue mechanism.

## Decisions

### 1. Lock-free Atomic Exp Mutation in `SessionData`
- **Decision**: Add a thread-safe atomic addition method on `SessionData` (e.g., using `AtomicIntegerFieldUpdater` with `Float.floatToRawIntBits` / `Float.intBitsToFloat` or `AtomicLongFieldUpdater` with double bits) to perform CAS-based lock-free addition without monitor locks.
- **Rationale**: Monitor locks (`synchronized (data)`) cause thread suspension and lock convoying under contention. A CAS loop takes nanoseconds and never blocks the calling thread, maintaining compatibility with existing serialization while being non-blocking.
- **Alternatives Considered**:
  - *Keep `synchronized`*: Leaves the main game loop vulnerable to blocking whenever other threads access `SessionData`.
  - *Replace `SessionData.exp` with `DoubleAdder`*: Requires custom Jackson serializers/deserializers and breaks direct field access in tests and legacy helpers.

### 2. Snapshot-based Serialization in `SessionRepository.write()`
- **Decision**: Decouple JSON serialization from the synchronization lock in `SessionRepository.write()`. Capture a shallow snapshot/copy of `SessionData` fields or extract the required values in a minimal, instantaneous step, and perform `JsonUtils.toJsonString(...)` outside any lock.
- **Rationale**: Serializing an object graph via Jackson takes orders of magnitude longer than copying a few primitive fields. Releasing any lock before JSON serialization guarantees background flushes never stall real-time game events.
- **Alternatives Considered**:
  - *Async queue with delayed writes*: Adds complexity and potential race conditions on server shutdown without solving the fundamental lock hold-time issue.

### 3. Level-Check Guard for Player Name Sync
- **Decision**: In `SessionService.updateLevel(Session session)`, move `session.player.name(...)` inside the condition `if (level != session.currentLevel)`.
- **Rationale**: Recalculating formatted names and sending player sync packets across the network is only necessary when a level changes. For the vast majority of exp gains that don't trigger a level up, this removes virtually all CPU and network overhead.
- **Alternatives Considered**:
  - *Debouncing player name updates with a timer*: Unnecessarily delays genuine level-up broadcasts; guarding on `level != session.currentLevel` delivers immediate updates on actual level-ups with zero overhead on non-level-up gains.

## Risks / Trade-offs

- **[Precision with Float CAS]** → Standard IEEE 754 float addition in CAS loops can encounter NaN or bitwise representation variations.
  *Mitigation*: Use standard `Float.floatToRawIntBits` and `Float.intBitsToFloat` in the compare-and-swap loop, ensuring exact arithmetic identical to `+=`.
- **[Concurrent Snapshot Consistency]** → A background flush might snapshot `playTime` and `exp` at slightly different microsecond moments if not coordinated.
  *Mitigation*: A quick shallow clone or minimal lock copy ensures snapshot consistency while keeping lock hold time negligible (<1 microsecond).
