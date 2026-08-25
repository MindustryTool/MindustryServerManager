## Why

`updateFlood()` runs every game tick and is the dominant CPU cost of `FloodGamemode`. Target servers run ~600×600 maps (360k tiles), so steady-state per-tick overhead matters. Verified against Arc/Mindustry source:

- `Seq.map()` / `Seq.select()` each allocate a fresh `Seq` + backing `Object[]` on every call (arc-core `Seq.java`), so `cores.map(...)` + `cores.select(...)` allocate two collections plus lambda captures per tick.
- `around(Building)` allocates a new `Seq<Tile>` per invocation and performs `4 × blockSize` bounds-checked `Tiles.get(x,y)` lookups; it runs once per unsuppressed core during re-seeding and once per processed tile in `spread()`.
- Once the BFS queue drains (all reachable tiles flooded — common on large maps), every subsequent tick pays `spreaded.clear()` (~45 KB bitset memset on 360k tiles) plus a full perimeter re-seed of all unsuppressed cores before hitting the update cap.
- The `updatedTiles` flush block iterates and clears every tick even when nothing transitioned.

We want to reduce this per-tick CPU/allocation churn without changing gameplay-visible behavior.

## What Changes

- Reduce per-tick allocations in `updateFlood()` / `spread()`:
  - Replace `cores.map(...)` + `unsuppressedCores = cores.select(...)` with a single reused scratch `Seq<Building>` filled by direct iteration over `Team.crux.cores()`, filtered by `suppressed.containsKey`.
  - Reuse a scratch `Seq<Tile>` for the perimeter walk in `around(Building)` instead of allocating a new Seq on each call.
  - Hoist invariant work out of loops: fetch `config.floodTiles.get(0)` (first tier) once per tick instead of per core-perimeter tile.
  - Skip the entire `updatedTiles` flush block when it is empty (no tile transitions happened this cycle).
  - Use `tile.pos()` (identical to `x + y*width` in Mindustry) or cached width for index math instead of re-reading `Vars.world.width()`.
- Keep everything else unchanged: tier lookups (`config.floodTiles` has fewer than 10 entries — scans are negligible), `Instant` time bookkeeping, core heal/reset logic, unit damage scan, suppression handling, UI updates.

## Capabilities

### New Capabilities
- `flood-gamemode`: Documents the required behavior of the flood gamemode (flood spread & tier evolution) as observable invariants that must hold after optimization.

### Modified Capabilities

(none — this is a performance-only change; no existing capability's requirements change)

## Impact

- Affected code: `plugin/src/main/java/plugin/gamemode/flood/FloodGamemode.java` only (`updateFlood`, `spread`, `around`); no API/signature changes visible outside the class.
- No database, translation, session, or config changes.
- Risk areas: flood spread ordering/timing randomness must remain statistically equivalent; net packet volume must not increase.
