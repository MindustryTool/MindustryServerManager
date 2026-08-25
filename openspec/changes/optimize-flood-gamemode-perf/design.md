## Context

`FloodGamemode` (plugin/src/main/java/plugin/gamemode/flood/FloodGamemode.java) implements the "flood" PvE gamemode: crux cores flood surrounding tiles with escalating block tiers. Profiling/analysis shows the dominant CPU stress comes from `updateFlood()`, which runs every game tick (`Trigger.update`) and delegates to `spread()`.

Current hot-path costs in `updateFlood()` / `spread()` (verified against Arc `Seq.java` and Mindustry `World.java`/`Tiles.java` source):

1. **Two intermediate Seq allocations per tick**: `Seq.map()` and `Seq.select()` in Arc each construct a new `Seq` with a fresh backing `Object[]` (map starts at source size, select at default 16 and grows), so `Team.crux.cores().map(...)` + `.select(c -> !suppressed.containsKey(c))` allocate two collections plus captured lambdas every tick even when nothing changed.
2. **Per-call Seq allocation in `around(Building)`**: allocates a new `Seq<Tile>` per invocation; called once per unsuppressed core during re-seeding and once per processed tile inside `spread()`'s while-loop. Each walk performs `4 × blockSize` bounds-checked `Tiles.get(x, y)` lookups (thin but repeated).
3. **Steady-state re-seed cost on large maps**: on ~600×600 maps the reachable flood area saturates, the queue drains, and every subsequent tick executes `spreaded.clear()` (memset over a 360k-bit BitSet) plus a full perimeter re-seed of all unsuppressed cores before the update cap short-circuits actual spreading.
4. **Unconditional flush block**: the `updatedTiles` entrySet iteration + clear runs every tick; when no tiles transitioned (common steady state) it does no useful work. The per-block `int[]` rebuild only occurs on transition ticks, so it is not a steady-state cost.

Non-costs (explicitly out of scope): `config.floodTiles` has fewer than 10 entries, so its `.find(...)` linear scans are negligible. `Instant.now()` calls, core heal/reset logic, the 100 ms unit-damage scan, suppression bookkeeping, and UI updates are not significant contributors and will not be touched. The `long[360_000] floods` array (~2.9 MB) is a one-time allocation — memory footprint is acceptable.

Constraints: behavior must remain gameplay-identical (same spread speed/distribution, same tier evolution timing). The plugin framework dispatches `@Trigger` methods; signatures and annotations must be preserved.

## Goals / Non-Goals

**Goals:**
- Eliminate the steady-state allocation churn and redundant work in `updateFlood()` / `spread()`, the measured dominant cost.
- Keep all observable behavior identical: spread speed/distribution, damage values, tier evolution.

**Non-Goals:**
- Changing tier lookups to a HashMap (`floodTiles` has <10 items — no meaningful gain).
- Migrating time bookkeeping from `Instant` to longs (not a hot-path contributor).
- Touching `update()`, `updateSuppress()`, `updateUnitDamgeOnFlood()`, or `updateUI()`.
- Multithreading the flood simulation or rewriting the scheduler framework.

## Decisions

1. **Build the unsuppressed-core list into a reused scratch `Seq<Building>`.**
   - One scratch field (`unsuppressedCoresScratch`), cleared and refilled each tick by iterating `Team.crux.cores()` directly, casting to `Building`, filtering with `suppressed.containsKey`. Replaces both `map` + `select` allocations.
   - Safe because all access is on the main thread within one invocation.
   - Alternative: stream/filter APIs — rejected, same allocations.

2. **Refactor `around(Building)` to fill a caller-provided/reused `Seq<Tile>`.**
   - Add an overload `around(Building core, Seq<Tile> dest)` that clears and fills `dest`; keep the allocating version delegating to it for any other callers. In `updateFlood()` re-seeding use one scratch Seq per loop; in `spread()` reuse a second scratch Seq across while-loop iterations.
   - Careful: `spread()` iterates neighbors of `tile.build` after polling from the queue — the destination Seq must not alias the queue itself; a dedicated scratch Seq is safe since neighbors are consumed before the next `around` call.
   - Alternative: replace Seq with a flat int[] of tile positions — more invasive; Seq reuse achieves most of the win.

3. **Guard the `updatedTiles` flush behind an emptiness check.**
   - `if (!updatedTiles.isEmpty()) { ...flush... }`. Removes entrySet iteration + clear cost in the common no-transition tick.
   - Alternative: dirty flag boolean — redundant; emptiness check is equivalent and simpler.

4. **Hoist loop-invariant lookups in the seeding path.**
   - `config.floodTiles.get(0)` is fetched inside the per-tile seeding loop; hoist to a local before the core loop. `Mathf.random` bounds and `firstTier.damage * multiplier` stay as-is (per-tile randomness is behavioral).

5. **Use cached width for index math.**
   - Mindustry's `Tile.pos()` already computes `x + y*width`; either use `tile.pos()` directly (it is exactly the current `index()` formula) or cache `Vars.world.width()` at rule application. Avoids repeated `Vars.world.width()` virtual calls inside the hot loop; also compute each neighbor index once per neighbor instead of via repeated `index()` calls.

## Risks / Trade-offs

- [Scratch collection reuse leaks state if call patterns change later] → Mitigation: fields are private, cleared at the start of each use; add brief comments marking them as main-thread scratch buffers.
- [`around` overload misuse could alias collections] → Mitigation: keep old allocating signature delegating to the new one so external call sites stay correct by default.
- [No automated test harness for gamemode] → Mitigation: manual smoke test checklist in tasks.md plus compile gates.

## Migration Plan

Single-plugin change; no data or config migration. Deploy by rebuilding the plugin jar and restarting the server. Rollback by redeploying previous jar.

## Open Questions

(none)
