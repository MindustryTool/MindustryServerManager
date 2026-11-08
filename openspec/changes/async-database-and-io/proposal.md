## Why

Database queries, disk writes, and JSON encoding currently execute synchronously on Mindustry's main game loop thread. When players join, leave, or start matches, the tick loop stalls and drops server TPS. This change moves all database access and map image generation off the main game thread.

## What Changes

- Load player session data asynchronously using `Tasks.io` on join; show a warning if commands run before the session finishes loading.
- Queue session writes to `Tasks.io` when players leave instead of saving synchronously on the main thread.
- Move daily login checks and rewards to run asynchronously after the session loads.
- Cache the best map record in memory on map start and save match completion records asynchronously.
- Preload map ratings into memory on startup and map change so `/rtv` and `/topmaps` do not query the database.
- Remove the duplicate `PlayEvent` listener in `ApiGateway`. Save the map file on the main thread and offload byte reading, Base64 encoding, and HTTP upload to `Tasks.io`.
- Replace the linear player lookup in `DailyTaskService.getByUuid` with an O(1) map lookup.

## Capabilities

### New Capabilities
None.

### Modified Capabilities
- `session-service`: Load sessions asynchronously on join and write them asynchronously on leave.
- `daily-login-bonus`: Run daily login checks asynchronously without blocking the main game thread.
- `map-records`: Cache best records in memory and record match results asynchronously.
- `map-rating`: Preload map ratings into memory to eliminate database queries in menus.
- `daily-tasks`: Use O(1) session lookups during bullet and build events.

## Impact

- `plugin.session.SessionService` and `plugin.session.SessionRepository`: Asynchronous session loading and write-behind persistence.
- `plugin.session.DailyService`: Asynchronous daily bonus processing.
- `plugin.maprecord.MapRecordService`: In-memory best record caching and async saving.
- `plugin.maprating.MapRatingService`: In-memory rating caching and preload on startup.
- `plugin.gateway.ApiGateway` and `plugin.utils.Utils`: Offload map preview creation to worker threads.
- `plugin.dailytask.DailyTaskService`: Direct map access for player lookup.
