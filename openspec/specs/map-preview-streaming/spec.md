# map-preview-streaming Specification

## Purpose
TBD - created by archiving change responsive-map-menu. Update Purpose after archive.
## Requirements
### Requirement: Asynchronous pre-rendered map texture streaming
The server SHALL stream existing pre-rendered map preview images to connected players asynchronously without running on-demand CPU map preview generation.

#### Scenario: Stream pre-rendered map preview when present
- **WHEN** a player opens the map menu
- **AND** an image file exists on disk matching the map's file name (e.g. `<filename>.png` or `<filename>.msav.png`)
- **THEN** the server asynchronously sends the image bytes to the player's connection via `Vars.netServer.sendTexture()`

#### Scenario: Missing pre-rendered image skipped
- **WHEN** a candidate map has no corresponding image file on disk
- **THEN** the server skips texture streaming for that map
- **AND** no synchronous or on-demand CPU map rendering is triggered

