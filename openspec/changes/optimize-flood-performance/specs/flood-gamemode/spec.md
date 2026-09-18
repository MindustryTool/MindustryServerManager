## MODIFIED Requirements

### Requirement: Flood spread behavior
The system SHALL track edge flood tiles (crux flood tiles adjacent to at least one spreadable unflooded tile) and initiate a spread wave from the active edge list every 8 seconds divided by the current multiplier (minimum 1 second). Spread wave execution SHALL be paced across consecutive ticks in bounded slices (up to 150–200 edge tiles per tick) until all active edge tiles in the current wave have been processed. When spreading, adjacent air/replaceable tiles reached by the edge tiles SHALL be placed as the first configured flood tier. Newly placed flood tiles with spreadable neighbors SHALL be enqueued to participate in the subsequent spread wave, and edge tiles with no remaining spreadable neighbors SHALL be removed. Flooded tiles SHALL evolve through configured tiers after their evolve time divided by the current multiplier plus 1–5 s jitter. Spread reach and tier progression SHALL match the configured flood tile list.

#### Scenario: Flood spreads from an unsuppressed core
- **WHEN** the game is running and at least one crux core is not suppressed
- **THEN** spreadable tiles on the core perimeter are seeded with the first flood tier and added as edge flood tiles to initiate spreading

#### Scenario: All cores suppressed halts spread
- **WHEN** every crux core is currently suppressed
- **THEN** the flood simulation is frozen (no placements, evolutions, or damage pulses) and resumes when suppression ends

#### Scenario: Edge flood tiles initiate spread on the spread cycle
- **WHEN** the spread interval elapses and active edge flood tiles exist
- **THEN** a spread wave begins and evaluates edge tiles in bounded slices across consecutive ticks without monopolizing a single frame

#### Scenario: Edge list updates as frontier advances
- **WHEN** an edge flood tile spreads and no longer has any spreadable neighbors
- **THEN** it is removed from the active edge list, and newly placed flood tiles with spreadable neighbors are registered for the subsequent spread wave

#### Scenario: Destroyed flood block restores neighbor to edge list
- **WHEN** a crux flood block is destroyed leaving an adjacent flood block with an open neighbor
- **THEN** the adjacent flood block is added back to the edge list and attempts spread into the cleared tile on the next spread wave

#### Scenario: Tier evolution proceeds through all configured tiers
- **WHEN** a flood tile has existed for its tier's evolve time / multiplier + jitter
- **THEN** it transitions to the next configured tier until the final tier is reached

### Requirement: Event-driven scheduling performance
The flood simulation SHALL use an event-driven min-heap scheduler with primitive parallel arrays such that ticks with no due events perform O(1) work, no per-tick allocations occur in steady state, standard Mindustry building invariants (`build.isValid() == true`) are preserved on all flood blocks to guarantee reliable engine and gamemode integration, and network tile-block updates are batched per block and flushed at most once per 100 ms window, chunked to at most 150–200 tile positions per packet. The first flush after simulation reset SHALL not be delayed by the window gate.

#### Scenario: No-op tick does not send network tile updates
- **WHEN** a tick completes with no tiles transitioning to new flood blocks
- **THEN** no `Call.setTileBlocks` packets are emitted for that tick

#### Scenario: Rapid transitions coalesce into capped packet batches per window
- **WHEN** tiles transition to new flood blocks on many consecutive ticks within a single 100 ms window
- **THEN** `Call.setTileBlocks` packets per affected block are emitted when the window opens, chunked into packets of at most 150–200 tile positions without exceeding network MTU

#### Scenario: Flood blocks preserve engine valid state
- **WHEN** a flood tile of any tier is placed or evolved
- **THEN** its building remains added to the engine with `isValid() == true`, allowing flood tier identification, unit damage, and edge tracking to function accurately

#### Scenario: Unit damage check performs O(1) lookup without allocation
- **WHEN** units are checked for contact damage on flood tiles
- **THEN** flood tier identification is performed via direct block ID indexing without heap or lambda allocations

#### Scenario: Downgrade emits single network dispatch
- **WHEN** a flood block is downgraded to a lower tier upon taking lethal damage
- **THEN** the downgrade block transition is emitted via immediate tile RPC without duplicate queueing into the periodic flush queue

#### Scenario: First flush after reset is immediate
- **WHEN** the flood simulation resets (map load) and tiles transition before any flush window has opened
- **THEN** those placements are emitted without waiting for an interval boundary

#### Scenario: Idle tick performs near-zero work
- **WHEN** all flooded tiles are waiting on future deadlines and no enemy structures are adjacent to the flood
- **THEN** the simulation performs only a heap-peek comparison and allocates nothing
