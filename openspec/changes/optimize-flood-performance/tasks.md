## 1. Crux Conveyor Sleeping & Downgrade Cleanup

- [x] 1.1 Put Crux conveyor flood blocks to sleep (`build.sleeping = true; build.remove()`) on the server upon placement and during flush to eliminate `Groups.build` ticking overhead
- [x] 1.2 Remove duplicate `pendingUpdatesByTier` enqueueing in `FloodSpreader.tryDowngradeTile()` to prevent duplicate `tile.setBlock()` execution and duplicate network packets

## 2. Paced Wave Spreading

- [x] 2.1 Add sliced wave iteration state to `FloodSpreader` with `MAX_SPREAD_PER_TICK = 150` edge tiles per frame
- [x] 2.2 Buffer newly created edge tiles into `nextWaveEdges` so they participate strictly in the subsequent 8-second wave
- [x] 2.3 Advance wave slices across consecutive ticks until the active wave completes, maintaining the 8-second interval / multiplier cadence

## 3. Capped Flush Slicing

- [x] 3.1 Cap `Call.setTileBlocks` batches in `FloodSpreader.flushUpdates()` to `MAX_FLUSH_PER_WINDOW = 150` tile positions per tier, deferring overflow to subsequent flush windows
- [x] 3.2 Apply sleep state to flushed conveyor blocks on the server during batch application

## 4. Unit Damage Fast-Path

- [x] 4.1 Refactor `FloodGamemode.updateUnitDamgeOnFlood()` to use `spreader.getFloodTier(tile)` direct $O(1)$ block lookup, eliminating lambda allocations

## 5. Testing & Verification

- [x] 5.1 Add unit tests in `FloodSpreaderTest` covering sliced wave progression, `nextWaveEdges` buffering, capped flushes, and conveyor sleep state
- [x] 5.2 Run `./gradlew test` and verify full test suite passes with zero regressions
