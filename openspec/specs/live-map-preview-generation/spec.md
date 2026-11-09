# live-map-preview-generation Specification

## Purpose

Live-map PNG generation from the current game state via the external image renderer, including the in-memory save-bytes hand-off and the on-disk PNG hand-off to `get-image`.

## Requirements
### Requirement: Live map preview generated from in-memory save bytes

The system SHALL generate the live-map preview PNG by saving the current game state with `SaveIO.write` to an in-memory deflated stream and POSTing those bytes to the external image renderer, without writing a temporary `.msav` file to disk.

#### Scenario: Successful generation writes PNG file

- **WHEN** a game is running and `generateMapPreview` runs (forced or throttle elapsed)
- **THEN** the plugin saves the world in-memory on the Mindustry main thread, POSTs the bytes, and writes `map-preview-image.png` to `Vars.dataDirectory`

#### Scenario: Save failure is loud and old PNG is preserved

- **WHEN** the in-memory save throws or exceeds the 3s main-thread timeout
- **THEN** the call fails loudly with an exception
- **AND** no new PNG is written, so the previous `map-preview-image.png` remains available to `get-image`

#### Scenario: Generation is throttled

- **WHEN** `generateMapPreview` is called with `force=false` within 3 minutes of the last attempt
- **THEN** no save, POST, or PNG write occurs

#### Scenario: PNG is still delivered via volume file

- **WHEN** the server manager requests `get-image` after `generate-map-image`
- **THEN** the PNG bytes are read from `map-preview-image.png` on the node volume (RPC contract remains `Void`, no PNG byte stream over RPC)
