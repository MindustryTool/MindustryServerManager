## Context

`FloodSpreader` and `FloodGamemode` manage flood spread, block progression, enemy damage pulses, and core suppression across Mindustry maps under 1 vCPU and 500MB RAM constraints.

In mid-to-late game on larger maps, the flood frontier accumulates 1,500 to 5,000+ active edge tiles. The current implementation suffers from three critical bottlenecks:
1. **Single-Tick Spread Spikes**: When the 8-second spread timer triggers, `spreadEdges()` traverses all edge tiles at once on the Mindustry main thread, performing 30,000+ neighbor queries and pushing thousands of entries into the event min-heap. Immediately thereafter, `flushUpdates()` calls `Call.setTileBlocks()`, synchronously invoking `Tile.setBlock()` on 1,500+ tiles in a single frame. This monopolizes the CPU for 100ms–300ms, causing noticeable frame drops and server lag.
2. **Conveyor Ticking Overhead**: The initial three tiers of the flood are `Blocks.conveyor`, `Blocks.titaniumConveyor`, and `Blocks.armoredConveyor`. In Mindustry, conveyor blocks have `update = true` and are added to `Groups.build`. When the flood spans 10,000–20,000 tiles, Mindustry executes `updateTile()` for thousands of idle Crux conveyors on the main thread 60 times per second (~900,000 calls/sec), permanently degrading TPS even between spread cycles.
3. **Uncapped Network Flushing & Redundant Dispatches**: `Call.setTileBlocks` flushes unbounded queues; `tryDowngradeTile` fires `Call.setTile` and redundantly enqueues into `pendingUpdatesByTier`; and `updateUnitDamgeOnFlood()` allocates lambdas every 100ms on the main thread.

## Goals / Non-Goals

**Goals:**
- **Zero Spread Slowdown & Identical Wave Feel**: Maintain the exact 8-second wave cadence (scaled by `multiplier`), identical wave frontier expansion, and unchanged flood tier visuals (conveyors evolving into walls).
- **Eliminate Spread Frame Hitches**: Slice edge spread traversal across consecutive frames (e.g. 150–200 edge tiles per tick over ~0.25s) so main-thread frame time remains under 4ms (rock-solid 60 FPS).
- **Eliminate Steady-State Conveyor Ticking**: Put Crux flood conveyors to sleep (`build.sleeping = true; build.remove()`), removing them from `Groups.build` while preserving health tracking, collision, damage processing, and client animations.
- **Cap Network & Block Flush Bursts**: Restrict `setTileBlocks` batches to 150–200 tile positions per flush window.
- **Zero Allocations & Clean Dispatches**: Use $O(1)$ block lookup for unit damage and eliminate duplicate network packets in `tryDowngradeTile`.

**Non-Goals:**
- Altering core perimeter seeding logic (core perimeter seeding remains untouched in `updateFlood()`).
- Changing tier evolution formulas, damage stats, or day/night timings.
- Introducing custom modded blocks or altering client RPC protocol.

## Decisions

### 1. Paced Wave Spreading (Sliced Frontier Traversal)
- **Decision**: When `now >= nextSpreadAt`, initiate a spread wave that processes up to `MAX_SPREAD_PER_TICK = 150` edge tiles per frame over consecutive ticks until the current wave frontier is fully evaluated.
- **Details**:
  - Edge tiles queued for the current wave are processed sequentially in sub-batches.
  - Newly placed flood tiles that qualify as edge tiles are enqueued into a `nextWaveEdges` scratch buffer, ensuring they only participate in the *next* 8-second wave and do not cause runaway cascades within the current wave.
  - Once the current wave completes (typically in 10–20 frames / ~0.25s), the spreader rests until `nextSpreadAt` (scheduled 8 seconds / `multiplier` from the wave start).
- **Rationale**: Keeps total wave spread time under a fraction of a second (visually imperceptible to players from an instantaneous burst) while cutting per-frame CPU load by over 90%.
- **Alternatives Considered**:
  - *Hard quota per 8-second interval (old `MAX_NEW_FLOOD_PER_TICK = 300`)*: Caused waves to stall and take 50+ seconds to circle the map, producing lopsided growth.
  - *Continuous single-tile trickling*: Destroys the tactical 8-second pulsing wave rhythm players rely on to build and react.

### 2. Sleeping Crux Flood Conveyors
- **Decision**: Whenever Crux flood conveyors (`conveyor`, `titaniumConveyor`, `armoredConveyor`) are placed on the server, invoke `build.sleeping = true; build.remove();`.
- **Details**:
  - In Mindustry, `build.remove()` only removes the entity from `Groups.build` (the per-tick update collection).
  - The building remains fully attached to `tile.build`, `Vars.indexer`, and `TeamData.buildingTree`.
  - Bullets hit the conveyor normally, `damage()` modifies health normally, and `BlockDestroyEvent` cleans it up normally.
  - Clients receive standard `setTileBlocks` packets and render animated conveyor textures locally.
  - Player conveyors on `Team.sharded` are unaffected.
- **Rationale**: Reduces server-side per-frame ticking overhead from ~900k calls/sec to 0, eliminating permanent TPS decay as the flood expands.
- **Alternatives Considered**:
  - *Replacing conveyors with non-ticking blocks (floors, environment, walls)*: Violates the requirement to keep the original visual identity and progression.
  - *Setting `Blocks.conveyor.update = false` globally*: Breaks player-built conveyors across the entire server.

### 3. Capped Flush Window & Sub-batching
- **Decision**: In `flushUpdates()`, limit each `Call.setTileBlocks` packet to at most `MAX_FLUSH_PER_WINDOW = 150` tile positions per tier. Any remaining queued updates remain in `pendingUpdatesByTier` and flush in subsequent 100ms windows.
- **Rationale**: Prevents huge bursts of `Tile.setBlock()` proximity and pathfinding recomputations on the server, and prevents packet oversized fragmentation over UDP.

### 4. Direct $O(1)$ Block Tier Lookup for Unit Damage
- **Decision**: Replace `config.floodTiles.find(t -> t.block == tile.build.block)` in `FloodGamemode.updateUnitDamgeOnFlood()` with `spreader.getFloodTier(tile)`.
- **Rationale**: Eliminates lambda object allocations on every unit check every 100ms, leveraging `FloodSpreader`'s existing flat primitive lookup array.

### 5. Single-Dispatch Downgrade
- **Decision**: In `tryDowngradeTile()`, rely strictly on `Call.setTile(tile, prev.block, Team.crux, 0)` for the immediate downgrade and remove the redundant `pendingUpdatesByTier[prevTierIndex].add(tile.pos())`.
- **Rationale**: Eliminates duplicate `Tile.setBlock()` calls and duplicate network packet dispatches for every damaged block.

## Risks / Trade-offs

- **[Risk] High player speed or rapid map traversal during wave slicing**
  → *Mitigation*: The entire wave resolves within 0.25–0.35 seconds, which is virtually indistinguishable from an instantaneous frame to human perception and standard Mindustry unit movement.
- **[Risk] Sleeping conveyor state inconsistencies upon tile destruction**
  → *Mitigation*: `Building.killed()` executes `tile.remove()`, which properly detaches `tile.build` regardless of `sleeping` status. Unit tests will verify destruction and downgrade behavior on sleeping buildings.
- **[Risk] Pending update backlog exceeding flush rate**
  → *Mitigation*: With 150 tiles per 100ms window, the flush capacity is 1,500 tiles per second, comfortably exceeding the generation rate of paced waves.
