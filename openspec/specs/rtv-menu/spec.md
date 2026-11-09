# rtv-menu Specification

## Purpose

Provide deterministic ordering and rendering for candidate maps in the rock-the-vote (RTV) selection menu without database-driven sorting side effects.
## Requirements
### Requirement: Deterministic map sorting
The `RtvMenu` SHALL sort available maps deterministically using precomputed stats without executing database queries during comparator evaluations.

#### Scenario: Unrated maps placed first
- **WHEN** candidate maps include unrated maps (0 votes or 0.0 rating) and rated maps
- **THEN** unrated maps are prioritized at the top of the menu before rated maps

#### Scenario: Ties broken by votes then name
- **WHEN** two candidate maps have identical rating scores
- **THEN** the map with higher total votes is placed first
- **AND** if total votes are equal, the maps are ordered alphabetically by name case-insensitively

#### Scenario: Pure comparator without database queries
- **WHEN** sorting candidate maps in `RtvMenu`
- **THEN** comparator evaluations use snapshot values without querying the database

### Requirement: Fullscreen responsive grid layout
The `RtvMenu` SHALL render in fullscreen mode (`fillScreen = true`) using a scrollable pane that expands to fill the available screen and contains an Arc `WrapTable` (`wrap(true)`), arranging map cards in a responsive multi-column grid.

#### Scenario: Responsive wrapping across viewports
- **WHEN** `RtvMenu` is rendered on any supported viewport
- **THEN** the menu sets `fillScreen = true`
- **AND** the content scroll pane fills available space with an inner `wrap(true)` container

### Requirement: Map preview image display
Each map card in `RtvMenu` SHALL include an image element referencing the map's network texture identifier with a fallback placeholder.

#### Scenario: Map card renders preview node
- **WHEN** building a card for an available map
- **THEN** an image element with region `"net-map-" + mapId` and placeholder `"nomap"` is positioned at the top of the card

### Requirement: Map commands unification
The `/maps` client command SHALL open the `RtvMenu` dialog instead of printing plain text to the player's chat.

#### Scenario: Command invocation opens menu
- **WHEN** a player executes `/maps` without arguments
- **THEN** the `RtvMenu` is displayed to the player

