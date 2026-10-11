## ADDED Requirements

### Requirement: Purchasing trails with money instead of level gating

The plugin SHALL require players to unlock trails by purchasing them with coins instead of gating them by player level. Unlocked trails SHALL be persisted in `session.getData().unlockedTrails`.

#### Scenario: Display price for unowned trail
- **WHEN** a player views `TrailMenu` and does not own a trail
- **THEN** the trail option displays its coin cost and a purchase indicator

#### Scenario: Successfully purchasing a trail
- **WHEN** a player selects an unowned trail and has at least the required coin cost
- **THEN** the cost is deducted from their wallet, the trail is added to `unlockedTrails`, the session is marked dirty, and the trail is equipped

#### Scenario: Insufficient funds for trail purchase
- **WHEN** a player selects an unowned trail and has fewer coins than the required cost
- **THEN** no coins are deducted, the trail is not unlocked, and an insufficient funds message is displayed

#### Scenario: Equipping an already owned trail
- **WHEN** a player selects a trail already present in `unlockedTrails`
- **THEN** no coins are charged and the trail is toggled active or inactive
