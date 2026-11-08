## 1. Asynchronous Session Loading & Persistence

- [ ] 1.1 Add `loading` state to `Session` and reject early commands in `ClientCommandHandler` with `session.loading`.
- [ ] 1.2 Implement asynchronous session fetching in `SessionService.onPlayerJoin` via `Tasks.io`, firing `SessionCreatedEvent` on main thread once loaded.
- [ ] 1.3 Update `SessionRepository.onSessionRemoved` to queue write-behind persistence to `Tasks.io` and evict from cache.
- [ ] 1.4 Optimize `SessionService.getByUuid` to use direct map lookup `data.get(uuid)` instead of linear scan.

## 2. Asynchronous Daily Bonus & Map Records

- [ ] 2.1 Update `DailyService.onSessionCreated` to execute login queries and writes asynchronously in `Tasks.io`.
- [ ] 2.2 Cache current map best record in memory in `MapRecordService` on map start.
- [ ] 2.3 Move `MapRecordRepository.saveAttempt` on game over to `Tasks.io`.

## 3. Warm Caching for Map Ratings

- [ ] 3.1 Implement whole-table map ratings preload in `MapRatingService` on startup and map change.
- [ ] 3.2 Update `/rtv` menu and `/topmaps` command to read directly from memory cache.
- [ ] 3.3 Offload rating vote submissions to `Tasks.io`.

## 4. Map Preview Generation & High-Frequency Events

- [ ] 4.1 Remove duplicate `@Listener(PlayEvent.class)` registration in `ApiGateway`.
- [ ] 4.2 Keep `SaveIO.save` on main thread and offload byte reading, Base64 encoding, and HTTP upload in `Utils.generateMapPreview` to `Tasks.io`.
- [ ] 4.3 Update `DailyTaskService` to use direct session lookup `sessionService.get(uuid)` in `onUnitBulletDestroy` and `onBlockBuildEnd`.
