# trail-selection

## Purpose

Provide trail selection and customization interface for players.
## Requirements
### Requirement: Explicit None option on trail menu
The `TrailMenu` SHALL render a "None" option as the first item in the trail selection list on page 0.

#### Scenario: Display None option on first page
- **WHEN** a player opens `/trail` or navigates to page 0 of `TrailMenu`
- **THEN** the menu SHALL display the localized "None" option as the top option of the list

#### Scenario: None option not displayed on subsequent pages
- **WHEN** a player navigates to page 1 or higher in `TrailMenu`
- **THEN** the menu SHALL display only trail items corresponding to that page without the "None" option header

### Requirement: Trail active status indication
The `TrailMenu` SHALL indicate whether no trail is currently equipped by decorating the "None" option with an active indicator dot.

#### Scenario: No trail active
- **WHEN** a player has no trail equipped (`session.getData().trail` is empty or null)
- **THEN** the "None" option SHALL be displayed with the active prefix `[accent]● [green]`

#### Scenario: Trail active
- **WHEN** a player has an active trail equipped
- **THEN** the "None" option SHALL be displayed with the regular prefix `[green]`

### Requirement: Disabling active trail
When a player selects the "None" option, the system SHALL clear the active trail, persist the session change, notify the player, and dismiss the menu.

#### Scenario: Selecting None option
- **WHEN** a player clicks the "None" option in `TrailMenu`
- **THEN** the system SHALL set `session.getData().trail` to an empty string, mark the session dirty in `SessionRepository`, send the localized `trail.removed` confirmation message to the player's chat, and close the menu

### Requirement: Trail localization catalog entries
The system SHALL provide localized strings for `trail.none` and `trail.removed` across all supported translation catalog JSON files.

#### Scenario: Localized display and notification
- **WHEN** `TrailMenu` renders the "None" button or sends the removal notification to a player
- **THEN** the text SHALL be resolved from the player's locale via `Tr.t` using `trail.none` and `trail.removed`

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

