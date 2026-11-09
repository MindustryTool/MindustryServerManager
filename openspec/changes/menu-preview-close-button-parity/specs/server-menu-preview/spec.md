## ADDED Requirements

### Requirement: Preview dialog includes default close button
The offscreen renderer SHALL add the default close/cancel button to the rendered dialog, matching the production `Menus.menuBuilder` behavior, so preview images include the 64 px cancel button and dialog proportions match in-game dialogs.

#### Scenario: Close button rendered in preview
- **WHEN** a developer captures a `ServerMenu` via `MenuSnapshot`
- **THEN** the rendered PNG includes the default cancel/close button below the menu content

#### Scenario: WelcomeMenu proportions match production
- **WHEN** `WelcomeMenu` is captured via `MenuSnapshot`
- **THEN** the WelcomeMenu buttons render at their configured height (36 px) alongside the 64 px cancel button, matching production layout