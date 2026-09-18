## Why

In the flood gamemode, large-scale flood progression caused severe main-thread lag spikes (100ms–300ms frame drops every spread interval) and continuous TPS degradation. This was caused by: (1) all active edge tiles attempting spread and pushing thousands of operations synchronously in a single frame; (2) un-chunked network packet dispatches with thousands of tiles; and (3) per-tick lambda allocations and duplicate packet dispatch during tile downgrades. This change optimizes the simulation and execution path without slowing down the flood spread or altering its visual progression, wave cadence, or gameplay feel.

## What Changes

- **Paced Wave Spreading**: When the spread cycle triggers (every 8s / multiplier), slice active edge tile checks across consecutive ticks (150–200 edge tiles per frame) via an isolated snapshot queue rather than executing thousands of edge expansions and heap operations in a single tick.
- **Preserved Engine Building Invariants**: Ensure all Crux flood tiles remain valid Mindustry buildings (`build.isValid() == true`) without calling `build.remove()`, preserving normal collision, unit contact damage, tier evolution, and edge tracking.
- **Chunked Packet Batching**: Chunk `Call.setTileBlocks` packets in the flush window to at most 150–200 tiles per packet, preventing oversized UDP packets while applying all tiles immediately without gameplay delay.
- **Unit Damage Fast-Path**: Refactor `FloodGamemode.updateUnitDamgeOnFlood()` to use `FloodSpreader`'s precomputed $O(1)$ direct block ID array (`spreader.getFloodTier(tile)`), eliminating lambda allocations on the 100ms main-thread loop.
- **Downgrade Packet Cleanup**: Remove redundant queueing into `pendingUpdatesByTier` within `tryDowngradeTile()`, preventing duplicate `tile.setBlock()` execution and duplicate network packets.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `flood-gamemode`: Spread execution transitions from a single-tick burst to a paced wave spread across consecutive frames using an isolated snapshot queue; network flush chunks tile updates to 150–200 positions per packet; unit contact damage utilizes direct $O(1)$ block lookup; duplicate downgrade packet dispatch is eliminated.

## Impact

- `plugin/src/main/java/plugin/gamemode/flood/FloodSpreader.java`: Paced wave state tracking, sliced edge iteration via snapshot queue, packet chunking, and downgrade packet cleanup.
- `plugin/src/main/java/plugin/gamemode/flood/FloodGamemode.java`: Unit damage fast-path via `spreader.getFloodTier(tile)`.
- No database or configuration schema changes. Network packets remain standard Mindustry RPCs (`Call.setTileBlocks`, `Call.setTile`).
