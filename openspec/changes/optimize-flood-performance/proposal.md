## Why

In the flood gamemode, large-scale flood progression causes severe main-thread lag spikes (100ms–300ms frame drops every spread interval) and continuous TPS degradation. This is caused by three key factors: (1) all active edge tiles attempting spread and flushing thousands of block placements synchronously in a single frame; (2) Crux flood conveyors ticking actively in Mindustry's `Groups.build` loop 60 times per second; and (3) unthrottled network and world updates along with per-tick lambda allocations and duplicate packet dispatch. This change optimizes the simulation and execution path without slowing down the flood spread or altering its visual progression, wave cadence, or gameplay feel.

## What Changes

- **Paced Wave Spreading**: When the spread cycle triggers (every 8s / multiplier), slice active edge tile checks and placements across consecutive ticks (150–200 edge tiles per frame over ~0.25s) rather than executing thousands of edge expansions and heap operations in a single tick.
- **Sleeping Crux Flood Conveyors**: Mark Crux flood conveyor buildings as sleeping upon placement (`build.sleeping = true; build.remove()`), removing them from Mindustry's `Groups.build` tick loop while preserving full health tracking, collision, damage processing, visual animation on clients, and tier progression.
- **Capped Flush Batching**: Cap the maximum number of tile positions emitted per `Call.setTileBlocks` flush window to 150–200 tiles, smoothing out network packet transmission and server-side `Tile.setBlock()` proximity/pathfinding recomputations.
- **Unit Damage Fast-Path**: Refactor `FloodGamemode.updateUnitDamgeOnFlood()` to use `FloodSpreader`'s precomputed $O(1)$ direct block ID array (`spreader.getFloodTier(tile)`), eliminating lambda allocations on the 100ms main-thread loop.
- **Downgrade Packet Cleanup**: Remove redundant queueing into `pendingUpdatesByTier` within `tryDowngradeTile()`, preventing duplicate `tile.setBlock()` execution and duplicate network packets.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `flood-gamemode`: Spread execution transitions from a single-tick burst to a paced wave spread across consecutive frames; network flush window caps tile updates to 150–200 positions per batch; Crux conveyor flood blocks are put to sleep on server-side tick loops; unit contact damage utilizes direct $O(1)$ block lookup; duplicate downgrade packet dispatch is eliminated.

## Impact

- `plugin/src/main/java/plugin/gamemode/flood/FloodSpreader.java`: Paced wave state tracking, sliced edge iteration, flush batch capping, conveyor sleep invocation, and downgrade packet cleanup.
- `plugin/src/main/java/plugin/gamemode/flood/FloodGamemode.java`: Unit damage fast-path via `spreader.getFloodTier(tile)`.
- No database or configuration schema changes. Network packets remain standard Mindustry RPCs (`Call.setTileBlocks`, `Call.setTile`).
