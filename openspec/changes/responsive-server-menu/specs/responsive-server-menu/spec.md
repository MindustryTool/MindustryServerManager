## ADDED Requirements

### Requirement: Responsive fullscreen server menu grid
The server list menus (`ServerListMenu` and `GlobalServerListMenu`) SHALL enable `fillScreen` and display servers in a scrollable, responsive wrap grid (`UiBuilder.table().wrap(true)`).

#### Scenario: Rendering server grid across viewports
- **WHEN** a player opens `/servers` or an administrator opens `/redirect`
- **THEN** the menu presents a fullscreen scroll pane containing server cards arranged in a responsive wrap table aligned to the top-left

### Requirement: Left-aligned vertical server card
Each server card in the grid SHALL be a vertical card containing a top preview image container, left-aligned title, player count, gamemode/map details, optional description, and a left-aligned action button.

#### Scenario: Server card layout formatting
- **WHEN** a server entry is rendered in the server menu grid
- **THEN** the server card displays an image widget at the top followed by left-aligned text metadata and an action button at the bottom

### Requirement: Missing image placeholder fallback
The server card image widget SHALL specify `"nomap"` as the placeholder texture so that cards without images maintain consistent dimensions.

#### Scenario: Displaying server with missing image
- **WHEN** a server has no preview image or the image endpoint returns HTTP 404
- **THEN** the server card displays the `"nomap"` placeholder texture without collapsing the image dimensions

### Requirement: Asynchronous image fetching and 5-minute in-memory caching
The system SHALL asynchronously fetch server preview images from `https://api.mindustry-tool.com/api/v4/servers/{id}/image` and cache both successful image payloads and negative 404 responses in-memory for 5 minutes.

#### Scenario: Cache hit for server image
- **WHEN** a server preview image is requested for a server whose image was cached within the last 5 minutes
- **THEN** the system retrieves the cached byte payload without making an external HTTP request

#### Scenario: Negative caching for 404 responses
- **WHEN** an external image request returns HTTP 404
- **THEN** the system caches the absence of the image for 5 minutes and suppresses subsequent HTTP queries for that server during the TTL

### Requirement: Server texture streaming to player clients
When a player opens a server list menu, the system SHALL stream cached or newly fetched server textures to the player's connection using `Vars.netServer.sendTexture(con, "net-server-" + id, bytes)`.

#### Scenario: Texture streamed to client connection
- **WHEN** a server preview image payload is available for a listed server
- **THEN** the system transmits the image bytes to the player client via `Vars.netServer.sendTexture`
