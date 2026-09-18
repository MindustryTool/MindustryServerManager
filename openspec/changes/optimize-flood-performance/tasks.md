## 1. Preserved Building Invariants & Downgrade Cleanup

- [x] 1.1 Preserve standard Mindustry building invariants (`build.isValid() == true`) without calling `build.remove()`, ensuring flood detection, damage, and evolution work reliably
- [x] 1.2 Remove duplicate `pendingUpdatesByTier` enqueueing in `FloodSpreader.tryDowngradeTile()` to prevent duplicate `tile.setBlock()` execution and duplicate network packets

## 2. Paced Wave Spreading via Snapshot Wave Queue

- [x] 2.1 Add sliced wave iteration state to `FloodSpreader` with `MAX_SPREAD_PER_TICK = 150` edge tiles per frame
- [x] 2.2 Isolate wave execution via `waveQueue` snapshot so newly added edges participate strictly in the subsequent 8-second wave
- [x] 2.3 Advance wave slices across consecutive ticks until the active wave completes, maintaining the 8-second interval / multiplier cadence

## 3. Capped Flush Packet Slicing

- [x] 3.1 Chunk `Call.setTileBlocks` packets in `FloodSpreader.flushUpdates()` to `MAX_FLUSH_PER_WINDOW = 150` positions per packet, preventing oversized UDP packets while applying tiles immediately without gameplay lag
- [x] 3.2 Ensure idempotent core perimeter seeding so tick-by-tick passes never duplicate pending tile queues

## 4. Unit Damage Fast-Path

- [x] 4.1 Refactor `FloodGamemode.updateUnitDamgeOnFlood()` to use `spreader.getFloodTier(tile)` direct $O(1)$ block lookup, eliminating lambda allocations

## 5. Testing & Verification

- [x] 5.1 Add unit tests in `FloodSpreaderTest` covering sliced wave progression, `waveQueue` snapshot isolation, and edge list updates
- [x] 5.2 Run `./gradlew test` and verify full test suite passes with zero regressions
