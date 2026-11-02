# server-menu-system Specification

## Purpose

Declarative, typed server menu framework backed by Mindustry build 160's `MenuBuilder` and `UiBuilder`, supporting scrollable panes, form fields, and stable callback dispatching.

## Requirements

### Requirement: Server menu framework uses build 160 MenuBuilder
The system SHALL register a centralized `MenuBuilderListener` using `Menus.registerMenuBuilder` and send menus to players using `Call.menuBuilder`.

#### Scenario: Displaying a menu to a player
- **WHEN** a menu is sent to a player connection
- **THEN** the system dispatches `Call.menuBuilder` using the registered menu builder ID and a unique session token

### Requirement: Correlation via unique session tokens
The system SHALL assign a unique incremental token to each displayed menu session and correlate incoming `MenuResult` callbacks by matching the token.

#### Scenario: Callback resolution on button click
- **WHEN** a player clicks a button in a displayed menu
- **THEN** the system receives the `MenuResult` with the matching token and executes the associated action callback

#### Scenario: Handling menu cancellation
- **WHEN** a player dismisses or closes a menu dialog without selecting a button
- **THEN** the system receives a cancelled `MenuResult`, clears the active menu for the player, and advances the queue

### Requirement: Backward compatibility for legacy PluginMenu
The system SHALL translate legacy `PluginMenu` option matrices into `MenuBuilder` table nodes without requiring changes to existing menu subclasses.

#### Scenario: Legacy PluginMenu execution
- **WHEN** an existing `PluginMenu` subclass requests to display
- **THEN** the system converts the menu's title, description, and button rows into `UiBuilder` nodes and displays them via `Call.menuBuilder`

### Requirement: Player menu queue management
The system SHALL maintain a FIFO queue of pending menus per player and present only one active menu at a time.

#### Scenario: Queue advancement after menu completion
- **WHEN** an active menu finishes or is dismissed and more menus are queued for that player
- **THEN** the system displays the next pending menu in the queue

#### Scenario: Session removal cleanup
- **WHEN** a player disconnects or their session is removed
- **THEN** all active and queued menus for that player are discarded
