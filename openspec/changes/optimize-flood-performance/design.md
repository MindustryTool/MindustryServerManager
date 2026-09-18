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
- **Preserve Mindustry Building Invariants**: Keep all Crux flood buildings active with `build.isValid() == true` without calling `build.remove()`, ensuring flood detection, health, unit damage, and evolution continue functioning accurately.
- **Chunk Network Packets**: Chunk `setTileBlocks` packets to 150–200 tile positions per packet to avoid UDP MTU overflow while applying all world updates immediately without visual or gameplay lag.
- **Zero Allocations & Clean Dispatches**: Use $O(1)$ block lookup for unit damage and eliminate duplicate network packets in `tryDowngradeTile`.

**Non-Goals:**
- Altering core perimeter seeding logic (core perimeter seeding remains untouched in `updateFlood()`).
- Changing tier evolution formulas, damage stats, or day/night timings.
- Introducing custom modded blocks or altering client RPC protocol.

## Decisions

### 1. Paced Wave Spreading (Snapshot Wave Queue)
- **Decision**: When `now >= nextSpreadAt`, snapshot the active `edgeTiles` into a dedicated `waveQueue` and process up to `MAX_SPREAD_PER_TICK = 150` edge tiles per frame over consecutive ticks until the snapshot is exhausted.
- **Details**:
  - Edge tiles snapshot for the current wave are processed sequentially via a monotonically increasing `waveIndex`.
  - Newly placed flood tiles that qualify as edge tiles are enqueued into `edgeTiles`. Because `waveQueue` is an isolated snapshot, newly placed tiles strictly wait for the *next* 8-second wave and do not cause cascading expansions in the current wave.
  - Removing an edge tile that has exhausted its spreadable neighbors uses O(1) swap-and-pop on `edgeTiles` without mutating `waveQueue` or its loop bounds, preventing skipped tiles or premature wave termination.
  - Once the snapshot wave completes, `isSpreadingWave` becomes false and the spreader rests until `nextSpreadAt` (scheduled 8 seconds / `multiplier` from the wave start).
- **Rationale**: Eliminates the frame hitch while guaranteeing that 100% of frontier edge tiles are evaluated with zero behavior drift.

### 2. Preserved Building Invariants (No Conveyor Removal)
- **Decision**: Avoid calling `build.remove()` on Crux conveyors.
- **Details**:
  - In Mindustry, `build.remove()` sets `added = false`.
  - The engine defines `isValid()` as `return added && !dead;`.
  - Calling `build.remove()` caused `build.isValid()` to return `false` on all flood conveyors, breaking `isFloodTile`, `getFloodTier`, min-heap evolution processing, and unit damage.
  - Idle Crux conveyors with no items have negligible overhead in Mindustry (a single branch check `items.total() > 0`), so preserving standard building validity guarantees 100% stable gameplay without performance degradation.

### 3. Chunked Network Packet Flushing
- **Decision**: In `flushUpdates()`, chunk `Call.setTileBlocks` packets to at most `MAX_FLUSH_PER_WINDOW = 150` tile positions per packet.
- **Rationale**: Prevents UDP packet drops and MTU fragmentation when large frontiers expand, while immediately applying all placed blocks to the world so tiles never remain as air on the server.

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
