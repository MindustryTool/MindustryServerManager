## Context

`ServerListMenu` and `GlobalServerListMenu` currently display servers in a single-column, text-heavy layout with fixed safe dimensions (`MenuContext.SAFE_WIDTH` and `MenuContext.SAFE_CARD_WIDTH`). The Mindustry client supports responsive `wrap(true)` layouts and texture streaming via `Vars.netServer.sendTexture(con, textureName, bytes)`. Additionally, the Mindustry Tool API exposes server banner graphics at `https://api.mindustry-tool.com/api/v4/servers/{id}/image`.

## Goals / Non-Goals

**Goals:**
- Provide a responsive fullscreen grid (`fillScreen = true`) for both `/servers` (`ServerListMenu`) and `/redirect` (`GlobalServerListMenu`).
- Display left-aligned vertical cards (~190–210px wide) featuring a top image preview, left-aligned title, player count, gamemode/map info, optional description, and a left-aligned action button.
- Stream server preview images to clients asynchronously using `sendTexture(con, "net-server-" + id, bytes)` with a `"nomap"` fallback for missing images or HTTP 404s.
- Cache fetched image bytes and negative 404 responses in-memory with a 5-minute expiration to prevent redundant network requests and avoid main-thread blocking.

**Non-Goals:**
- Persisting image bytes to local disk (in-memory 5-minute cache requested).
- Altering the backend API schema or authentication.
- Modifying redirect execution behavior (`ServerUtils.redirect` / `redirectAll`).

## Decisions

### 1. In-Memory 5-Minute Image Cache
- **Choice**: Store fetched image bytes and negative 404 markers in an in-memory cache with a 5-minute TTL.
- **Rationale**: Server banner images are ~1MB PNGs. In-memory caching eliminates repeated HTTP calls during navigation while respecting the user's preference for an in-memory-only 5-minute cache. Negative caching prevents repeated 404 queries.
- **Alternatives Considered**:
  - *Disk cache*: Rejected per user decision.
  - *No cache*: Severe bandwidth overhead and API hammering on every menu open.

### 2. Left-Aligned Vertical Card Layout
- **Choice**: Vertical cards with `width(200f)` in a `wrap(true)` table. Top preview image (`192x108f`), left-aligned text details (`labelAlign("left")`), and left-aligned action button.
- **Rationale**: Aligns with user's requirement ("do kinda the same for server menu but align left"). Matches the responsive grid feel of `RtvMenu` while structuring rich server metadata cleanly.
- **Alternatives Considered**:
  - *Horizontal split cards*: Takes up more horizontal space and fits fewer cards per row on mobile screens.

### 3. Missing Image Fallback
- **Choice**: Render `UiBuilder.image("net-server-" + id).placeholder("nomap")`.
- **Rationale**: If the server has no image or the endpoint returns 404, the `"nomap"` placeholder ensures card dimensions stay consistent across the grid.
- **Alternatives Considered**:
  - *Omitting the image element*: Causes jagged, mismatched card heights in the same row.

### 4. Asynchronous Fetch and Streaming
- **Choice**: On menu build, invoke an asynchronous IO task (`Tasks.io`) that queries the cache/API and dispatches `Vars.netServer.sendTexture(player.con, "net-server-" + id, bytes)` when available.
- **Rationale**: Network IO must never block the server tick loop or menu response time.

## Risks / Trade-offs

- **[Risk] High memory usage from 1MB PNGs in memory cache** → **Mitigation**: Cache entries expire after 5 minutes, limiting active memory usage to recently viewed servers.
- **[Risk] Endpoint rate limits or slow responses** → **Mitigation**: Async fetch with short timeouts (3–5s) and negative caching for failures so subsequent opens render immediately with the fallback.
- **[Risk] Texture streaming before client menu dialog renders** → **Mitigation**: Mindustry's client-side `TextureStreamEvent` caches received textures by name and dynamically updates existing image widgets when the texture arrives.
