## 1. Gamemode Loop Throttling

- [ ] 1.1 Replace `@Trigger(Trigger.update)` with `@Schedule(fixedDelay = 500, unit = TimeUnit.MILLISECONDS)` on `ZigerGamemode.tick()`.
- [ ] 1.2 Move `blockSpawner.spawn()` and unit spawner calls in `CataliGamemode` to a 1-second scheduled task.
- [ ] 1.3 Move core healing and boss timer updates in `CataliGamemode` to a 1-second scheduled task.

## 2. Thread Safety

- [ ] 2.1 Add `@MainThread` annotation to `CoreUnitUpgradeService.maintainUpgrades()`.
