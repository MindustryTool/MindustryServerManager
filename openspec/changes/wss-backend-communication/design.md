## Context

The Mindustry Server Manager daemon (`server` module) coordinates game server Docker containers on host machines. Currently, the manager and the central Backend API (`api.mindustry-tool.com`) communicate using two-way HTTPS:
1. Manager calls outbound HTTPS endpoints on Backend API (`/managers/request-connection`, `servers/{id}/login`).
2. Backend API calls inbound HTTPS endpoints and SSE streams on Manager port 8088 (`/api/v2/servers/{id}/**`).

This architecture creates severe self-hosting barriers:
- The manager host must have a public IP or port forwarding enabled on home/NAT routers.
- The manager host must configure and manage SSL/TLS certificates or an HTTPS reverse proxy.
- A symmetric `securityKey` must be securely distributed and stored on both the manager and backend to sign and verify JWT tokens.

To solve this, the manager will shift to a persistent outbound WebSocket Secure (`wss://`) connection to the central Backend API.

## Goals / Non-Goals

**Goals:**
- Eliminate the requirement for public IPs, port forwarding, and local SSL/TLS configuration on the Server Manager.
- Remove the symmetric `securityKey` configuration (`SECURITY_KEY_V2`) from the manager in favor of a persistent agent `accessToken` verified by the backend during the WSS handshake.
- Streamline all control commands, status queries, and event streams through a bidirectional RPC protocol (`WsMessage`).
- Implement high-performance, non-blocking chunked binary file transfers (maps, mods, save files) over WebSocket.
- Retain local WebSocket communication (`ws://server-manager:8088/gateway`) strictly for containerized Mindustry plugins on the local Docker network, while removing all public HTTP routes (`/api/v2/*`).

**Non-Goals:**
- Multi-instance distributed backend routing (e.g., Redis Pub/Sub). The backend runs as a single instance; distributed routing is out of scope.
- In-socket JWT token rotation. The `accessToken` is a persistent node/agent token validated on connection establishment.
- Altering the game plugin's internal gateway protocol (`plugin/gateway/ApiGateway.java`). Local container-to-manager communications remain untouched.

## Decisions

### Decision 1: Outbound WSS Client using Standard Java 11+ HttpClient
- **Choice**: Use `java.net.http.HttpClient` with `newWebSocketBuilder()` to manage the outbound WSS connection.
- **Rationale**: `HttpClient` is already integrated into the project (`HttpClients.shared()`), native to Java 17, and adds zero third-party library overhead.
- **Alternatives Considered**:
  - *Jetty WebSocket Client*: Heavyweight, unnecessary when JDK standard client provides full reactive WebSocket support.
  - *Keep Inbound HTTPS with Cloudflare Tunnels*: Requires users to install and configure `cloudflared` daemon on their machines, adding external setup complexity.

### Decision 2: Authentication via Handshake Token
- **Choice**: The manager sends `Authorization: Bearer <accessToken>` as an HTTP header (or fallback query parameter) during the initial WebSocket upgrade handshake.
- **Rationale**: Central Backend API validates the token once against its database/auth service during handshake. Once the connection is upgraded, the session is authenticated.
- **Alternatives Considered**:
  - *Symmetric HMAC key (`securityKey`)*: Requires distributing and synchronizing a shared secret, preventing easy node revocation.
  - *In-session refresh token messages*: Unnecessary complexity for a persistent daemon node service.

### Decision 3: Multiplexed RPC Envelope (`WsMessage`)
- **Choice**: Mirror the existing `WsMessage` protocol used between manager and plugin (`id`, `responseOf`, `type`, `payload`, `error`).
- **Rationale**: Proven, already implemented in `GatewayService.java`, and provides seamless asynchronous correlation for RPC calls (`CompletableFuture`).
- **Alternatives Considered**:
  - *JSON-RPC 2.0*: Adds different conventions without providing additional capability over the existing codebase's `WsMessage`.

### Decision 4: Binary Frame Chunking for File Transfers
- **Choice**: Transmit files using raw binary WebSocket frames prefixed with a 20-byte binary header (`UUID transferId` [16 bytes] + `int chunkIndex` [4 bytes]), preceded by a JSON `file-transfer-init` message and concluded by `file-transfer-complete`.
- **Rationale**:
  - Zero encoding overhead (unlike Base64 which adds 33% payload bloat).
  - 64KB chunking prevents Head-of-Line blocking, allowing ping/pong heartbeat frames and high-priority control messages to interleave freely.
- **Alternatives Considered**:
  - *Base64 JSON payloads*: Excessively high memory and CPU overhead for 20MB–50MB map and save files.
  - *Direct S3/R2 presigned URLs*: Requires S3/R2 infrastructure not currently configured for direct node storage.

### Decision 5: Strip Javalin HTTP Endpoints, Retain `/gateway`
- **Choice**: Remove all `/api/v2/*` HTTP endpoints from `ServerMain.java`. Keep Javalin listening on port 8088 strictly for local Docker container plugin connections to `ws://...:8088/gateway`.
- **Rationale**: Removes attack surface and obsolete HTTP handling while preserving internal container networking.

## Risks / Trade-offs

- **[Risk] WebSocket Idle Disconnects by Proxies/Load Balancers** → **Mitigation**: Implement mandatory 20-second ping/pong heartbeats and TCP keepalive.
- **[Risk] Reconnection Storm (Thundering Herd) on Backend Restart** → **Mitigation**: Implement exponential backoff (initial 1s, doubling up to 30s) with randomized jitter (+/- 25%) in `BackendWsClient`.
- **[Risk] State Desynchronization during Downtime** → **Mitigation**: Upon every successful WSS connection/reconnection, manager immediately sends a `sync-state` message reporting all currently running Mindustry server containers, ports, and statuses.
- **[Risk] Large File Transfer Socket Stalling** → **Mitigation**: Strict 64KB chunk size with backpressure windowing to ensure control and ping/pong frames are never starved.

## Migration Plan

1. Update `EnvConfig.java` to replace `SECURITY_KEY_V2` and `SERVER_URL` with `BACKEND_WS_URL`.
2. Implement `BackendWsClient`, `BackendRpcHandler`, and `BackendFileTransferService`.
3. Update `ServerMain.java` to remove all `/api/v2/*` routes, initialize `BackendWsClient`, and start event bridging to Backend.
4. Update Docker configuration and documentation (`SERVER.md`, `docker-compose.yml`) to remove port forwarding guidelines and `SECURITY_KEY_V2`.
5. Rollback strategy: Keep git commit history clean so the REST/SSE endpoints can be restored if needed before retiring backend HTTPS routes.
