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

### Requirement: Domain menus use native ServerMenu
All server menus across hub, moderation, voting, progression, cosmetics, and gamemodes SHALL inherit from `ServerMenu<T>` and build layouts using `MenuContext<T>`. Menus SHALL rely on the Mindustry client's built-in dialog close button for dismissal and MUST NOT render duplicate manual close buttons.

#### Scenario: Server menu renders native UiBuilder layout
- **WHEN** any server menu is triggered for a player
- **THEN** the menu builds its layout directly via `MenuContext<T>` and displays to the client without legacy translation

#### Scenario: Menu dismissal via built-in dialog close button
- **WHEN** a server menu is rendered on the client
- **THEN** the menu layout contains only domain actions and pagination controls, leaving dismissal to the built-in dialog close button

### Requirement: Menu context close button API removal
`MenuContext<T>` SHALL NOT expose a `closeButton` helper method, enforcing the use of the client dialog's built-in close button.

#### Scenario: Building menus without manual close button API
- **WHEN** a developer builds a menu layout using `MenuContext<T>`
- **THEN** no `closeButton` method is available on `MenuContext`

### Requirement: Player menu queue management
The system SHALL maintain a FIFO queue of pending menus per player and present only one active menu at a time.

#### Scenario: Queue advancement after menu completion
- **WHEN** an active menu finishes or is dismissed and more menus are queued for that player
- **THEN** the system displays the next pending menu in the queue

#### Scenario: Session removal cleanup
- **WHEN** a player disconnects or their session is removed
- **THEN** all active and queued menus for that player are discarded

### Requirement: Responsive mobile layout constraints
The system SHALL constrain server menu widths and scroll pane heights to prevent off-screen clipping on compact mobile viewports in both landscape and portrait orientations.

#### Scenario: Mobile landscape vertical containment
- **WHEN** a menu with a scrollable pane is displayed to a mobile connection
- **THEN** the scroll pane maximum height is constrained so the entire dialog fits within the 360px vertical screen boundary

#### Scenario: Mobile portrait horizontal containment
- **WHEN** a menu with cards, labels, or buttons is displayed in portrait orientation
- **THEN** all description labels wrap within safe bounds and table widths do not exceed 320px

### Requirement: MenuContext UiBuilder convenience methods
`MenuContext<T>` SHALL expose convenience helper methods for constructing `image`, `imageButton`, `buttonTable`, `stack`, and `defaults` elements directly on the root table, automatically registering actions for interactive elements.

#### Scenario: Adding an image to menu context
- **WHEN** a developer calls `ctx.image(region)` or `ctx.image(region, configure)`
- **THEN** an `ImageBuilder` node configured with the region is created, added to the root table, and returned

#### Scenario: Adding an image button with an action
- **WHEN** a developer calls `ctx.imageButton(icon, action)`
- **THEN** a unique action token is registered and an `ImageButtonBuilder` node with the action attached is added to the root table

#### Scenario: Adding a clickable button table
- **WHEN** a developer calls `ctx.buttonTable(builder, action)`
- **THEN** a unique action token is registered and a `ButtonTableBuilder` node populated with children is added to the root table

#### Scenario: Adding a stack container
- **WHEN** a developer calls `ctx.stack(builder)`
- **THEN** a `StackBuilder` node populated with overlaid children is created, added to the root table, and returned

#### Scenario: Setting cell defaults
- **WHEN** a developer calls `ctx.defaults(configure)`
- **THEN** a `DefaultsBuilder` node configured with cell properties is added to the root table and returned

