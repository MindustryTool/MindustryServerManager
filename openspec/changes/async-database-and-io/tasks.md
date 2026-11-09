## 1. Session Persistence & Lookup

- [x] 1.1 Queue write-behind persistence to `Tasks.io` in `SessionRepository.onSessionRemoved` and evict from cache.
- [x] 1.2 Optimize `SessionService.getByUuid`/`get(uuid)` to use direct map lookup `data.get(uuid)`.

## 2. Asynchronous Daily Bonus & Map Records

- [x] 2.1 Update `DailyService.onSessionCreated` to execute login queries and writes asynchronously in `Tasks.io`.
- [x] 2.2 Cache current map best record in memory in `MapRecordService` on map start.
- [x] 2.3 Move `MapRecordRepository.saveAttempt` on game over to `Tasks.io`.

## 3. Warm Caching for Map Ratings

- [x] 3.1 Implement whole-table map ratings preload in `MapRatingService` on startup and map change.
- [x] 3.2 Update `/rtv` menu and `/topmaps` command to read directly from memory cache.
- [x] 3.3 Offload rating vote submissions to `Tasks.io`.

## 4. Map Preview Generation & High-Frequency Events

- [x] 4.1 Remove duplicate `@Listener(PlayEvent.class)` registration in `ApiGateway`.
- [x] 4.2 Keep `SaveIO.save` on main thread and offload byte reading, Base64 encoding, and HTTP upload in `Utils.generateMapPreview` to `Tasks.io`.
- [x] 4.3 Update `DailyTaskService` to use direct session lookup `sessionService.get(uuid)` in `onUnitBulletDestroy` and `onBlockBuildEnd`.
