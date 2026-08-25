## ADDED Requirements

### Requirement: Flood spread behavior is preserved
The system SHALL spread flood tiles outward from unsuppressed crux cores using the existing BFS-style queue, evolving flooded tiles through configured tiers after their randomized evolve delay scaled by the flood multiplier. Optimizations MUST NOT change the distribution of spread timing or tier progression visible to players.

#### Scenario: Flood spreads from an unsuppressed core
- **WHEN** the game is running and at least one crux core is not suppressed
- **THEN** air/replaceable tiles adjacent to crux-built tiles become flooded within the same randomized delay window as before optimization

#### Scenario: All cores suppressed halts spread
- **WHEN** every crux core is currently suppressed
- **THEN** no new flood spreading occurs

### Requirement: Flood update performance characteristics
The per-tick flood update (`updateFlood` / `spread`) SHALL avoid steady-state allocations and redundant work: the unsuppressed-core list SHALL be built into a reused collection instead of allocating intermediate sequences each tick, neighbor perimeter walks SHALL reuse a destination collection instead of allocating per call, and network tile-block updates SHALL only be sent when tiles actually changed — all without altering observable gameplay behavior.

#### Scenario: No-op tick does not send network tile updates
- **WHEN** an update cycle completes with no tiles transitioning to new flood blocks
- **THEN** no `Call.setTileBlocks` packets are emitted for that cycle

#### Scenario: Steady-state tick allocates no intermediate sequences
- **WHEN** a tick runs with unchanged core state and no tile transitions
- **THEN** no new Seq instances are allocated for core filtering or perimeter walking

#### Scenario: Spread behavior is statistically unchanged
- **WHEN** flooding proceeds over multiple minutes after optimization
- **THEN** spread reach and tier evolution timing match the pre-optimization randomized windows (5–10 s initial delay, configured evolve times with 1–5 s jitter)
