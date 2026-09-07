## MODIFIED Requirements

### Requirement: Centralized exp gain via ExpGainEvent

The plugin SHALL add exp to a player's stored counter only through a single event mechanism: firing `ExpGainEvent(session, amount)` (defined in `plugin.session`, carrying the `Session` and a `long` amount). A single listener SHALL be the only place that mutates the exp counter: it applies the event amount non-blockingly without monitor synchronization, marks the session dirty, and recomputes the player's level.

#### Scenario: Firing the event grants exp
- **WHEN** `ExpGainEvent(session, X)` is fired
- **THEN** the session's stored `data.exp` increases by `X` via a non-blocking atomic update, the session is marked dirty, and the player's level is recomputed

#### Scenario: Concurrent exp gains do not block
- **WHEN** multiple `ExpGainEvent` instances are fired concurrently or in rapid succession
- **THEN** each event applies its exp increment atomically without blocking the invoking thread on monitor locks

### Requirement: Level derived from the stored exp counter

The player's level SHALL be computed exclusively from the stored exp counter via `ExpUtils.levelFromTotalExp(data.exp)`. Level-up messages and player name formatting SHALL reflect the stored counter, and player name updates SHALL be triggered only when a player's level actually changes or during session initialization.

#### Scenario: Level reflects accumulated counter
- **WHEN** a player's `data.exp` reaches a value that maps to a higher level
- **THEN** `SessionService.update` raises `session.currentLevel`, updates the player name, and broadcasts the level-up message

#### Scenario: Name update skipped when level does not change
- **WHEN** an exp gain increases `data.exp` but the computed level remains equal to `session.currentLevel`
- **THEN** `session.currentLevel` remains unchanged and player name formatting and network name synchronization packets are not sent

#### Scenario: Info string uses stored exp
- **WHEN** a player runs `/me`
- **THEN** the displayed total exp and level come from the stored `data.exp` counter
