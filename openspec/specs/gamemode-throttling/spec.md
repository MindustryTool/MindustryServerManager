# gamemode-throttling Specification

## Purpose
Schedule gamemode maintenance and spawning routines on periodic intervals instead of continuous 60 TPS triggers.

## Requirements

### Requirement: Throttled Building Replenishment in Ziger
`ZigerGamemode` SHALL replenish fillable buildings on a periodic schedule with a fixed delay of 500 milliseconds rather than executing on every game tick.

#### Scenario: Building refill schedule
- **WHEN** the Ziger gamemode is active and running
- **THEN** building inventories are inspected and replenished at 500ms intervals
- **AND** no building iteration occurs on individual game ticks

### Requirement: Throttled Spawner and Core Maintenance in Catali
`CataliGamemode` SHALL execute block spawning, enemy unit spawning checks, boss spawn progression, and core healing on a 1-second periodic schedule rather than on every game tick.

#### Scenario: Block and unit spawning in Catali
- **WHEN** the Catali gamemode is active
- **THEN** block spawning and unit spawning routines execute at 1-second intervals
- **AND** block network update packets are not transmitted on consecutive game ticks

### Requirement: Main-Thread Unit Upgrade Maintenance
`CoreUnitUpgradeService.maintainUpgrades` SHALL execute on the main game thread to safely mutate player unit health without cross-thread concurrency conflicts.

#### Scenario: Scheduled upgrade maintenance
- **WHEN** `maintainUpgrades` executes
- **THEN** unit health modifications occur on the Mindustry main thread
