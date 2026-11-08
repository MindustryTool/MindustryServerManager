## Context

Mindustry runs a single-threaded game tick loop at 60 TPS. The `plugin` module currently executes several blocking database operations and large disk saves directly on this tick thread:
- `SessionService.onPlayerJoin` triggers synchronous database reads.
- `SessionRepository.onSessionRemoved` performs synchronous database writes when players leave.
- `DailyService.onSessionCreated` executes synchronous queries for daily bonuses.
- `MapRecordService` and `MapRatingService` run synchronous database queries during player join, map change, game over, and menu display.
- `Utils.generateMapPreview` performs world saves, file reads, Base64 encodings, and HTTP calls synchronously inside a duplicate `PlayEvent` listener.
- `DailyTaskService` performs linear searches across sessions on every bullet and block event.

## Goals / Non-Goals

**Goals:**
- Eliminate all synchronous database queries and writes from the Mindustry tick thread.
- Move heavy disk read/write and Base64 operations out of `PlayEvent`.
- Provide responsive feedback if a player attempts commands before session data finishes loading.
- Replace linear scans with O(1) lookups in high-frequency event handlers.

**Non-Goals:**
- Changing gamemode update loop frequencies (handled in `gamemode-tick-throttling`).
- Modifying action filter reflection mechanisms (handled in `optimize-action-filters`).
- Overhauling database table schemas.

## Decisions

### 1. Asynchronous Session Loading with Placeholder State
- On player join, `SessionService` registers a placeholder session marked with `loading = true`.
- An asynchronous task in `Tasks.io` queries the database via `sessionRepository.get(uuid)`.
- When the query completes, `Core.app.post` updates the session on the main thread, clears `loading`, and fires `SessionCreatedEvent`.
- If a player enters a command before loading finishes, `ClientCommandHandler` rejects the command and sends `Tr.t(player, "session.loading")`.
- *Alternative considered*: Blocking until loaded (rejected: stalls the main thread for 10-50ms).
- *Alternative considered*: Silent ignore of commands (rejected: confusing to players).

### 2. Immediate Write-Behind Queue on Disconnect
- When a player leaves, `SessionRepository.onSessionRemoved` queues an asynchronous write task via `Tasks.io("save session " + uuid, ...)` and evicts the session from cache.
- *Alternative considered*: Retaining cache entries until 10-second timer flushes (rejected: wastes memory and risks stale re-entry).

### 3. Asynchronous Daily Bonus Check
- `DailyService.onSessionCreated` runs `dailyRepository.getLastLogin` and `setLastLogin` inside `Tasks.io`.
- Successful checks dispatch bonus awards and messages back to the player via `Core.app.post`.

### 4. Warm Cache for Map Ratings and Records
- `MapRatingService` preloads all ratings (`SELECT * FROM map_ratings`) into memory on startup and map change. `RtvMenu` and `/topmaps` read directly from memory.
- `MapRecordService` caches the current map's best record in memory on start, and offloads `saveAttempt` on game over to `Tasks.io`.

### 5. Offloaded Map Preview Generation
- Remove duplicate `@Listener(PlayEvent.class)` in `ApiGateway`.
- Keep `SaveIO.save(tempFile)` on the main thread for world consistency.
- Offload reading bytes, Base64 encoding, JSON formatting, and HTTP dispatch to `Tasks.io`.

### 6. O(1) Session Lookup
- Change `DailyTaskService` from calling `sessionService.getByUuid(uuid)` (linear search) to `sessionService.get(uuid)` (direct `ConcurrentHashMap.get`).

## Risks / Trade-offs

- **[Early player departure before DB load finishes]** → Discard the pending load result if the player is no longer connected.
- **[Server shutdown during pending writes]** → `SessionRepository.destroy()` flushes all remaining writes and awaits pending `Tasks.io` tasks before exiting.
