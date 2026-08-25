## 1. Unsuppressed-core list allocation removal

- [ ] 1.1 Add private scratch `Seq<Building>` field; in `updateFlood()`, clear and fill it by iterating `Team.crux.cores()` directly (cast to `Building`, filter with `suppressed.containsKey`), replacing the `cores.map(...)` + `cores.select(...)` allocations
- [ ] 1.2 Update downstream uses (`unsuppressedCores.isEmpty()`, re-seeding loop) to reference the scratch list

## 2. Perimeter walk allocation removal

- [ ] 2.1 Add `around(Building core, Seq<Tile> dest)` overload that clears and fills `dest`; keep existing `around(Building)` delegating to it
- [ ] 2.2 In `updateFlood()` re-seeding, reuse one scratch `Seq<Tile>` across all unsuppressed cores instead of allocating per call
- [ ] 2.3 In `spread()`, reuse a dedicated scratch `Seq<Tile>` for neighbor iteration across while-loop iterations

## 3. Flush block and loop-invariant work

- [ ] 3.1 Wrap the `updatedTiles` flush block in an `isEmpty()` check so no-change ticks skip entrySet iteration and clear
- [ ] 3.2 Hoist `config.floodTiles.get(0)` out of the per-tile seeding loop into a local computed once per `updateFlood()` invocation
- [ ] 3.3 Cache map width (or use `Tile.pos()`, which is identical to the current `index()` formula) and compute each neighbor index once in `spread()`

## 4. Verification

- [ ] 4.1 Build the plugin and confirm compile success
- [ ] 4.2 Manual smoke test: flood spreads from unsuppressed cores at comparable timing; tiles evolve through tiers; destroyed crux flood blocks reset
- [ ] 4.3 Manual smoke test: spread pauses when all cores suppressed, resumes after suppression expires
- [ ] 4.4 Confirm reduced GC/allocation churn vs before (e.g., profiler or allocation sampling on a loaded server) and that no `setTileBlocks` bursts occur during idle/no-change cycles
