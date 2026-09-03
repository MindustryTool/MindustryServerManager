# flood-gamemode

## Purpose

Defines the flood gamemode mechanics, including core-anchored flood spreading, tier evolution, connectivity-based orphan sweeping, damage pulses, core suppression accounting, and day/night cycle behaviors.
## Requirements
### Requirement: Flood spread behavior
The system SHALL track edge flood tiles (crux flood tiles adjacent to at least one spreadable unflooded tile) and attempt to spread new flood tiles from the active edge list every 5 seconds. When spreading, adjacent air/replaceable tiles reached by the edge tiles SHALL be placed as the first configured flood tier. Newly placed flood tiles with spreadable neighbors SHALL be added to the edge list, and edge tiles with no remaining spreadable neighbors SHALL be removed. Flooded tiles SHALL evolve through configured tiers after their evolve time divided by the current multiplier plus 1–5 s jitter. Spread reach and tier progression SHALL match the configured flood tile list.

#### Scenario: Flood spreads from an unsuppressed core
- **WHEN** the game is running and at least one crux core is not suppressed
- **THEN** spreadable tiles on the core perimeter are seeded with the first flood tier and added as edge flood tiles to initiate spreading

#### Scenario: All cores suppressed halts spread
- **WHEN** every crux core is currently suppressed
- **THEN** the flood simulation is frozen (no placements, evolutions, or damage pulses) and resumes when suppression ends

#### Scenario: Edge flood tiles attempt spread every 5 seconds
- **WHEN** 5 seconds elapse and active edge flood tiles exist
- **THEN** the spreader attempts to place the first configured flood tier onto adjacent spreadable tiles from the edge list

#### Scenario: Edge list updates as frontier advances
- **WHEN** an edge flood tile spreads and no longer has any spreadable neighbors
- **THEN** it is removed from the edge list, and newly placed flood tiles that have spreadable neighbors are added to the edge list

#### Scenario: Destroyed flood block restores neighbor to edge list
- **WHEN** a crux flood block is destroyed leaving an adjacent flood block with an open neighbor
- **THEN** the adjacent flood block is added back to the edge list and attempts spread into the cleared tile on the 5-second cycle

#### Scenario: Tier evolution proceeds through all configured tiers
- **WHEN** a flood tile has existed for its tier's evolve time / multiplier + jitter
- **THEN** it transitions to the next configured tier until the final tier is reached

### Requirement: Flood tiles require core connectivity
A flood tile SHALL remain active only while it is connected to at least one unsuppressed crux core through a chain of scheduled flood tiles (plus core footprints). Connectivity SHALL be validated by a periodic sweep (approximately every 5 seconds) that retires every scheduled tile unreachable from any unsuppressed core; retired tiles stop evolving, pulsing damage, and propagating.

#### Scenario: Cutting a flood corridor orphans the region
- **WHEN** players destroy the chain of flood blocks connecting a region to every crux core
- **THEN** within one sweep interval all tiles of that disconnected region retire and cease spreading, evolving, and dealing damage

#### Scenario: Connected regions are unaffected
- **WHEN** the connectivity sweep runs while a region still has a path to an unsuppressed core
- **THEN** its tiles remain active with their existing deadlines

### Requirement: Event-driven scheduling performance
The flood simulation SHALL use an event-driven min-heap scheduler with primitive parallel arrays such that ticks with no due events perform O(1) work, no per-tick allocations occur in steady state, and network tile-block updates are batched per block and flushed at most once per 100 ms window, only when tiles actually transitioned since the last flush. The first flush after simulation reset SHALL not be delayed by the window gate.

#### Scenario: No-op tick does not send network tile updates
- **WHEN** a tick completes with no tiles transitioning to new flood blocks
- **THEN** no `Call.setTileBlocks` packets are emitted for that tick

#### Scenario: Rapid transitions coalesce into one flush per window
- **WHEN** tiles transition to new flood blocks on many consecutive ticks within a single 100 ms window
- **THEN** exactly one `Call.setTileBlocks` packet per affected block is emitted when the window opens, containing every placement accumulated during that window

#### Scenario: First flush after reset is immediate
- **WHEN** the flood simulation resets (map load) and tiles transition before any flush window has opened
- **THEN** those placements are emitted without waiting for an interval boundary

#### Scenario: Idle tick performs near-zero work
- **WHEN** all flooded tiles are waiting on future deadlines and no enemy structures are adjacent to the flood
- **THEN** the simulation performs only a heap-peek comparison and allocates nothing

### Requirement: Pulsed damage to enemy structures
Flooded tiles SHALL damage valid adjacent enemy buildings by their tier's damage times the current multiplier once per second while enemies remain adjacent; enemy buildings on unsuppressed core perimeters SHALL receive equivalent first-tier pulses. Fully evolved tiles with no adjacent enemies SHALL retire from the simulation.

#### Scenario: Flood erodes adjacent enemy structure
- **WHEN** an enemy building stands next to a flooded tile of a given tier
- **THEN** it takes that tier's damage multiplied by the current flood multiplier approximately once per second

#### Scenario: Retired tiles stop consuming events
- **WHEN** a fully evolved flood tile has no adjacent enemy buildings
- **THEN** it leaves the event heap and performs no further work

### Requirement: Core damage accounting and suppression
The system SHALL accumulate damage dealt to crux cores across ticks and mark a core as suppressed for `config.suppressTime` when accumulated damage since the last check exceeds `config.suppressThreshold`. The gamemode SHALL be won when all cores are simultaneously suppressed.

#### Scenario: Sustained fire suppresses a core
- **WHEN** players deal more than `suppressThreshold` cumulative damage to a core between suppression checks
- **THEN** that core is marked suppressed until `suppressTime` elapses

#### Scenario: All cores suppressed triggers win
- **WHEN** the number of suppressed cores equals the total core count
- **THEN** a `FloodWonEvent` and a `GameOverEvent` for Team.sharded are fired

### Requirement: Day/night cycle timing
The system SHALL alternate between day (`dayDuration`) and night (`nightDuration`), toggling the lighting rule via a rules sync at each transition, incrementing the day counter at each dawn.

#### Scenario: Day transitions to night
- **WHEN** the elapsed daytime equals `dayDuration`
- **THEN** lighting is enabled, rules are synced to clients, and the cycle timer resets

### Requirement: Night unit spawning
The system SHALL spawn escalating unit types (atrax → spiroct → arkyid → toxopid by day count) near random crux cores every 30 seconds during night, up to a cap of 50 crux units, spawning one unit per suppressed core count entry.

#### Scenario: Units spawn at night based on day count
- **WHEN** it is night, fewer than 50 crux units exist, and the day counter is within a tier range
- **THEN** units of that tier are spawned at random crux cores

### Requirement: Player HUD updates once per second
The system SHALL update each player's info popup once per second showing flood multiplier percent, suppressed core count vs total, time until next cycle change, and current day count; labels SHALL render above suppressed cores. Locale-specific text SHALL continue to be used per player group.

#### Scenario: Popup reflects current state
- **WHEN** one second elapses during play
- **THEN** each player sees an updated popup with the current multiplier, suppression counts, countdown, and day number

### Requirement: Enemy units take damage on flooded tiles
The system SHALL periodically apply the matching flood tier's damage to non-crux units standing on tiles occupied by a crux building whose block matches a flood tile configuration.

#### Scenario: Unit stands on flooded tile
- **WHEN** a non-crux unit occupies a tile with a crux building of a configured flood block
- **THEN** the unit takes that tier's configured damage per check

