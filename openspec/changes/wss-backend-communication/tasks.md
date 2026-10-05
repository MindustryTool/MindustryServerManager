## 1. Configuration & Dependency Updates

- [ ] 1.1 Update `EnvConfig.java` to remove `securityKey` (`SECURITY_KEY_V2`) and `serverUrl`, and introduce `backendWsUrl` (`BACKEND_WS_URL`) with fallback defaults.
- [ ] 1.2 Update configuration templates (`docker-compose.yml`, `SERVER.md`) to document the outbound WSS architecture and updated environment variables.

## 2. Backend WSS Client Integration (Powered by `:gateway`)

- [ ] 2.1 Instantiate `JdkWsClient` from `:gateway` in `server` module configured with `backendWsUrl` and `Authorization: Bearer <accessToken>`.
- [ ] 2.2 Wire connection listener to trigger `sync-state` handshake message immediately upon connection to report all active server containers.

## 3. Bidirectional RPC & Event Bridging

- [ ] 3.1 Register RPC handlers on the backend `WsRpcChannel` to route incoming commands (`host`, `remove`, `pause`, `sendCommand`, `getPlayers`, `updatePlayer`, etc.) to `ServerService` and `GatewayService`.
- [ ] 3.2 Subscribe to `EventBus` in `server` to stream all `BaseEvent` instances to the Backend API over `WsRpcChannel.sendEvent()`.

## 4. Chunked Binary File Streaming

- [ ] 4.1 Wire `FileChunkStreamer` and `FileChunkReceiver` from `:gateway` to handle file downloads and uploads for container configurations and maps.
- [ ] 4.2 Validate checksum integrity and safe path traversal when persisting received files.

## 5. Server Main Cleanup & Verification

- [ ] 5.1 Remove all `/api/v2/*` HTTP route registrations from `ServerMain.java`, retaining only the `/gateway` WebSocket handler on port 8088.
- [ ] 5.2 Wire lifecycle management of the backend WSS client into `ServerMain.java` startup and shutdown hooks.
- [ ] 5.3 Verify project builds and passes tests with `./gradlew :server:build`.
