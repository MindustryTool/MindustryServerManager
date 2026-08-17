# vote-afk-exclusion

## Purpose

AFK players are excluded from all vote systems: their votes do not count toward passing and they do not contribute to the required-vote threshold.

## Requirements

### Requirement: AFK players do not count toward the required threshold

Every vote system SHALL compute its required-vote threshold from the number of connected players who are not AFK (`SessionService.countActive()`), not from the total connected player count.

#### Scenario: Threshold uses active players only
- **WHEN** the required vote count is computed for RTV, VNW, or a grief vote
- **THEN** the denominator is the number of sessions where `session.isAfk()` is false

#### Scenario: All players AFK
- **WHEN** every connected session is AFK and a vote is counted
- **THEN** the required threshold is at least 1 (clamped to a minimum)
- **AND** no vote passes because zero counted votes from active players can reach the threshold

### Requirement: AFK players' stored votes are not counted

Every vote system SHALL exclude votes from AFK sessions when counting current votes.

#### Scenario: Voted then went AFK
- **WHEN** a player who already cast a vote becomes AFK during the vote window
- **THEN** their vote no longer counts toward the threshold
- **AND** their vote counts again if they become ACTIVE before the vote resolves

#### Scenario: Vote count display excludes AFK votes
- **WHEN** the current vote count is displayed to players
- **THEN** the displayed count excludes votes from AFK players

### Requirement: Votes are accepted from all players

Every vote system SHALL accept a vote from any player, including a player whose session is currently AFK. No vote SHALL be rejected based on AFK state, and casting a vote SHALL NOT alter the player's AFK state.

#### Scenario: AFK player casts a vote
- **WHEN** an AFK player invokes `/rtv yes`, `/vnw`, or `/grief`
- **THEN** the vote is recorded normally
- **AND** the player's AFK state is not modified

#### Scenario: Vote counted once player is active
- **WHEN** a player who cast a vote is not AFK when the vote is counted
- **THEN** their vote counts toward the threshold

### Requirement: Admin vote behavior unchanged

Admin votes SHALL retain their existing instant/force behavior and SHALL be exempt from AFK exclusion.

#### Scenario: Admin forces RTV
- **WHEN** an admin selects a map in the RTV menu
- **THEN** the map is changed immediately without requiring votes, regardless of AFK state

#### Scenario: Admin passes VNW
- **WHEN** an admin casts a VNW vote and the active count requirement is not met
- **THEN** the vote still passes because the admin path is exempt