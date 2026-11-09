## Why

Server browsing menus (`ServerListMenu` and `GlobalServerListMenu`) currently render servers in a single-column, text-heavy vertical list with fixed safe widths. This layout does not showcase server preview graphics, underutilizes widescreen displays, and does not match the modern responsive grid design introduced for maps in `RtvMenu`.

## What Changes

- Update `ServerListMenu` and `GlobalServerListMenu` to use `fillScreen = true` with a responsive wrap grid (`UiBuilder.table().wrap(true)` inside a scroll pane).
- Lay out server cards vertically with left-aligned contents: top image preview, left-aligned title, status, gamemode/map details, description, and action button.
- Asynchronously fetch server preview images from `https://api.mindustry-tool.com/api/v4/servers/{id}/image`.
- Implement an in-memory 5-minute TTL cache for both successful image downloads and negative 404 responses.
- Display a fallback placeholder (`nomap`) for servers that lack an image or return 404, keeping card heights uniform across grid rows.
- Stream loaded server textures to connecting player clients via `Vars.netServer.sendTexture(con, "net-server-" + id, bytes)`.

## Capabilities

### New Capabilities
- `responsive-server-menu`: Responsive fullscreen grid layout for server selection menus with left-aligned cards, texture streaming, and in-memory 5-minute image caching.

### Modified Capabilities
<!-- None -->

## Impact

- `plugin/src/main/java/plugin/hub/ServerListMenu.java`: Rewrite layout to use responsive wrap table, left-aligned cards, texture streaming, and placeholder fallback.
- `plugin/src/main/java/plugin/hub/GlobalServerListMenu.java`: Rewrite layout to mirror `ServerListMenu` layout with admin redirect-all actions.
- Caching service / helper (e.g. `ServerImageCache` or integrated in hub service): Asynchronous HTTP fetcher with 5-minute in-memory cache and 404 negative cache.
- Tests: Add unit tests for image fetching/caching and menu construction, plus snapshot preview tests for layout validation.
