# flood-gamemode

## Requirements

### Requirement: Flood spread behavior
The system SHALL spread flood tiles outward from unsuppressed crux cores using an event-driven scheduler: air/replaceable tiles reached by the flood frontier SHALL be placed as the first flood tier after a randomized 5–10 s delay, and flooded tiles SHALL evolve through configured tiers after their evolve time divided by the current multiplier plus 1–5 s jitter. Spread reach and tier progression SHALL match the configured flood tile list.

#### Scenario: Flood spreads from an unsuppressed core
- **WHEN** the game is running and at least one crux core is not suppressed
- **THEN** air/replaceable tiles on the core perimeter are flooded immediately and frontier tiles flood within the randomized delay window

#### Scenario: All cores suppressed halts spread
- **WHEN** every crux core is currently suppressed
- **THEN** the flood simulation is frozen (no placements, evolutions, or damage pulses) and resumes when suppression ends

#### Scenario: Destroyed flood block stops simulating
- **WHEN** a crux building that was part of the flood is destroyed
- **THEN** its pending events are discarded and it does not re-enter the simulation unless re-seeded

#### Scenario: First tier placed when spread deadline fires on air tile
- **WHEN** a spread deadline (5-10s after frontier reached tile) fires on an air/replaceable tile
- **THEN** the first configured flood tier is placed on that tile and its neighbors are scheduled with new 5-10s delays

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
