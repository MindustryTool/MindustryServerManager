## Context

Currently, `plugin.update.PluginData` performs direct HTTP calls using `arc.util.Http` and `HttpURLConnection` to `https://api.mindustry-tool.com/api/v4/plugins/version` and `download`. `PluginUpdater` checks for updates every 5 minutes (`@Schedule(delay = 1, fixedDelay = 5, unit = TimeUnit.MINUTES)`).

In this change, the plugin delegates all version checks and binary downloads to the server manager through the existing WebSocket bridge (`ApiGateway`), reducing the check delay to 1 minute and using server-side Caffeine caching to avoid overwhelming the upstream API.

## Goals / Non-Goals

**Goals:**
- Eliminate direct WAN HTTP calls from `PluginData`, routing calls through `ApiGateway.sendRequest(...)`.
- Add server-side caching using Caffeine with a 5-minute TTL (`expireAfterWrite(5, TimeUnit.MINUTES)`) for both version metadata and binary jar payloads keyed by `owner/repo/tag`.
- Replace the existing unused `get-plugin-version` handler with the new upstream version check handler, and add `download-plugin` message handling.
- Reduce `PluginUpdater.checkUpdate` fixed delay from 5 minutes to 1 minute.
- Implement graceful skip/retry on the plugin side if the WebSocket connection is down during a scheduled check.

**Non-Goals:**
- Creating a separate HTTP server or standalone download port for transferring jar files (binary data will flow over the WebSocket connection).
- Fallback to direct HTTP on the plugin if the server manager is unreachable (the plugin will fail-fast, skip the cycle, and let the 1-minute schedule retry).

## Decisions

### Decision 1: Shared DTOs in the `dto` module
- **Decision**: Define `PluginQueryDto` (with fields `owner`, `repo`, `tag`) and `PluginVersionDto` (with field `updatedAt`) in the `dto` module.
- **Rationale**: Shared DTOs ensure serialization contract consistency between the plugin and server modules without manual JSON tree parsing.
- **Alternatives Considered**: Custom Map/JsonNode parsing in handlers; rejected to maintain type safety and consistency across the codebase.

### Decision 2: Replace `get-plugin-version` and Add `download-plugin` Handlers
- **Decision**: Reuse the message type `"get-plugin-version"` for plugin-to-server version checks (replacing the unused stub) and introduce `"download-plugin"` for jar downloads.
- **Rationale**: The previous `get-plugin-version` handler was an unused stub in `GatewayService`. Repurposing it keeps naming intuitive without legacy clutter.

### Decision 3: Server-side Plugin Cache Service
- **Decision**: Create or integrate a cache service on the server manager backed by Caffeine with a 5-minute write-expiration TTL for both plugin version queries and jar byte payloads.
- **Rationale**: Upstream API requests are deduplicated and cached. If multiple game nodes check or download simultaneously, only a single upstream fetch is executed.
- **Alternatives Considered**: Direct pass-through without caching; rejected because a 1-minute polling interval across multiple nodes would risk rate-limiting upstream.

### Decision 4: Binary Transfer via WebSocket Base64 Payload
- **Decision**: Return the downloaded jar bytes as a byte array (serialized via Base64 by Jackson in the JSON message payload) in response to `"download-plugin"`.
- **Rationale**: Reuses existing `WsMessage` architecture without adding HTTP routes or port mapping complexity to Docker containers.

## Risks / Trade-offs

- **[Risk]** WebSocket message size for multi-megabyte jar files.
  → **Mitigation**: Ensure WebSocket buffer sizes in the plugin's `nv-websocket-client` and the server's Javalin/Jetty configuration accommodate payloads up to at least 20MB.
- **[Risk]** Server manager offline or restarting when plugin checks for updates.
  → **Mitigation**: Plugin catches exceptions, logs a warning, and skips execution. The 1-minute schedule will retry automatically once the connection is re-established.
