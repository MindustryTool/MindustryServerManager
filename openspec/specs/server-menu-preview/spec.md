# server-menu-preview Specification

## Purpose

Offscreen rendering tool for developer preview and snapshot verification of `ServerMenu` instances, producing pixel-accurate PNG images without running a live server or game client.
## Requirements
### Requirement: Offscreen menu rendering to image
The system SHALL provide a programmatic API to build and render a `ServerMenu` instance into PNG image files across viewport variants using Mindustry client UI components.

#### Scenario: Successful menu image capture across viewports
- **WHEN** a developer passes a `ServerMenu` and target file path to `MenuSnapshot.capture`
- **THEN** the system builds the menu UI, renders it offscreen for all viewport variants, and writes valid PNG image files with viewport suffixes to the target path

#### Scenario: Specific viewport capture
- **WHEN** a developer specifies a single viewport via the snapshot builder
- **THEN** the system renders only that viewport and writes a valid PNG image file with the corresponding viewport suffix

### Requirement: Configurable image framing
The system SHALL support both tight dialog cropping and full-screen framing when exporting menu images, defaulting to full-screen capture.

#### Scenario: Full-screen capture by default
- **WHEN** menu capture is requested with default options
- **THEN** the output PNG image contains the full viewport with dimmed backdrop and centered menu dialog

#### Scenario: Tight dialog crop mode
- **WHEN** menu capture is requested with `fullScreen(false)`
- **THEN** the output PNG image dimensions match the bounding box of the rendered dialog

### Requirement: Mock session and player context
The system SHALL provide a mock session builder to configure player properties such as locale, player name, and admin permissions without requiring a live connection.

#### Scenario: Menu localization using mock session
- **WHEN** a menu is rendered with a mock session specifying a specific locale
- **THEN** localized text entries in the menu resolve according to that specified locale

### Requirement: Dedicated dev and test execution scope
The system SHALL isolate offscreen rendering and desktop assets to test and development scopes without including them in production plugin runtime jars.

#### Scenario: Production build packaging
- **WHEN** the production plugin jar is built
- **THEN** desktop client classes and assets are excluded from the output artifact

### Requirement: Compact mobile viewport simulation
The system SHALL support simulating compact mobile screen resolutions down to 288px width and mobile connection modes during offscreen menu rendering.

#### Scenario: Rendering in compact mobile landscape
- **WHEN** menu capture is requested with `viewport(720, 360)` and mobile mode enabled
- **THEN** the system resizes the scene to 720x360, enables mobile scaling, and renders the dialog within the compact landscape viewport

#### Scenario: Rendering in compact mobile portrait
- **WHEN** menu capture is requested with `MenuViewport.MOBILE_PORTRAIT`
- **THEN** the system resizes the scene to 288x512, enables mobile scaling, sets connection view width to 288px, and renders the dialog within the 288px portrait viewport

### Requirement: Preview dialog includes default close button
The offscreen renderer SHALL add the default close/cancel button to the rendered dialog, matching the production `Menus.menuBuilder` behavior, so preview images include the 64 px cancel button and dialog proportions match in-game dialogs.

#### Scenario: Close button rendered in preview
- **WHEN** a developer captures a `ServerMenu` via `MenuSnapshot`
- **THEN** the rendered PNG includes the default cancel/close button below the menu content

#### Scenario: WelcomeMenu proportions match production
- **WHEN** `WelcomeMenu` is captured via `MenuSnapshot`
- **THEN** the WelcomeMenu buttons render at their configured height (36 px) alongside the 64 px cancel button, matching production layout

