## Why

Currently, each running game server plugin directly polls `https://api.mindustry-tool.com` via HTTP every 5 minutes in `PluginUpdater` and directly downloads `.jar` binaries over public HTTP. This causes redundant external WAN requests across multiple game server nodes, lacks centralized caching, leaves nodes vulnerable to external network hiccups/rate limits, and checks for updates with unnecessary delay (5 minutes instead of 1 minute).

Routing version queries and plugin binary downloads through the existing WebSocket gateway to the server manager centralizes network access, allows local caching of version metadata and binary jar payloads with a 5-minute TTL, and enables a more responsive 1-minute check interval without overloading the remote MindustryTool API.

## What Changes

- **Plugin Update Delegation**: Move version queries (`get-plugin-version`) and binary downloads (`download-plugin`) from direct HTTP in `PluginData` to WebSocket requests routed through `ApiGateway`.
- **Offline / Disconnect Handling**: If the WebSocket is disconnected when `checkUpdate()` runs, fail-fast and skip without direct HTTP fallback; the next scheduled tick will retry.
- **Server-Side Plugin Caching**: Implement caching in the server manager with a 5-minute TTL using Caffeine for both plugin version responses and downloaded binary jar bytes keyed by `owner/repo/tag`.
- **Scheduled Check Interval**: Reduce `PluginUpdater.checkUpdate` fixed delay from 5 minutes to 1 minute (`@Schedule(delay = 1, fixedDelay = 1, unit = TimeUnit.MINUTES)`).
- **Shared DTOs**: Define DTOs in the `dto` module for plugin version query requests, version responses, and plugin download requests/responses.

## Capabilities

### New Capabilities
- `server-plugin-proxy`: Handles incoming WebSocket requests from plugins to check plugin versions and download plugin binaries with a 5-minute server-level cache against the upstream API.

### Modified Capabilities
- `plugin-updater-gamemode-restart`: The update check schedule interval changes from 5 minutes to 1 minute, and the retrieval mechanism delegates to the server manager over WebSocket rather than direct HTTP.

## Impact

- **Affected Code**:
  - `dto`: Add request and response DTOs for plugin version checks and plugin downloads.
  - `plugin`: `PluginUpdater` schedule annotation updated; `PluginData` replaced direct HTTP calls (`arc.util.Http`, `HttpURLConnection`) with `ApiGateway.sendRequest(...)`.
  - `server`: `GatewayService` registers handlers for `get-plugin-version` and `download-plugin`; a new or existing server service handles caching and upstream fetching.
- **Dependencies & APIs**: No new third-party dependencies required; Caffeine and WebSocket infrastructure are already available.
