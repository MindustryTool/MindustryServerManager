## Why

Gamemode logic in Ziger and Catali runs every tick at 60 TPS (`@Trigger(Trigger.update)`). Iterating through thousands of buildings to replenish resources and repeatedly spawning blocks and units every frame consumes significant CPU time on the game thread. In addition, `CoreUnitUpgradeService` mutates unit health on a background thread without main thread synchronization.

## What Changes

- Throttle `ZigerGamemode.tick()` by replacing `@Trigger(Trigger.update)` with `@Schedule(fixedDelay = 500, unit = TimeUnit.MILLISECONDS)`.
- Throttle `CataliGamemode` block spawning, boss spawn checking, and core healing to run on a 1-second interval via `@Schedule(fixedDelay = 1, unit = TimeUnit.SECONDS)`.
- Add `@MainThread` to `CoreUnitUpgradeService.maintainUpgrades()` so entity health adjustments execute safely on the main thread.

## Capabilities

### New Capabilities
- `gamemode-throttling`: Schedule gamemode maintenance and spawning routines on periodic intervals instead of continuous 60 TPS triggers.

### Modified Capabilities
None.

## Impact

- `plugin.gamemode.ziger.ZigerGamemode`: Change update trigger to a 500ms fixed delay schedule.
- `plugin.gamemode.catali.CataliGamemode`: Change per-tick update and spawn triggers to 1-second fixed delay schedules.
- `plugin.shop.CoreUnitUpgradeService`: Add `@MainThread` annotation to `maintainUpgrades`.
