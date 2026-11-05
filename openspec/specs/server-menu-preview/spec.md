# server-menu-preview Specification

## Purpose

Offscreen rendering tool for developer preview and snapshot verification of `ServerMenu` instances, producing pixel-accurate PNG images without running a live server or game client.

## Requirements

### Requirement: Offscreen menu rendering to image
The system SHALL provide a programmatic API to build and render a `ServerMenu` instance into a PNG image file using Mindustry client UI components.

#### Scenario: Successful menu image capture
- **WHEN** a developer passes a `ServerMenu` and target file path to `MenuSnapshot.capture`
- **THEN** the system builds the menu UI, renders it offscreen, and writes a valid PNG image file to the target path

### Requirement: Configurable image framing
The system SHALL support both tight dialog cropping and full-screen framing when exporting menu images.

#### Scenario: Tight dialog crop by default
- **WHEN** menu capture is requested with default options
- **THEN** the output PNG image dimensions match the bounding box of the rendered dialog

#### Scenario: Full-screen capture mode
- **WHEN** menu capture is requested with `fullScreen(true)`
- **THEN** the output PNG image contains the full viewport with dimmed backdrop and centered menu dialog

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
