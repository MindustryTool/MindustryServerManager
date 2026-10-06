# jdk-websocket-client

## Purpose

TBD - created by syncing change extract-gateway-subproject. Update Purpose after implementation.

## Requirements

### Requirement: Standard JDK 17 WebSocket Client
The `:gateway` module SHALL provide `JdkWsClient` wrapping Java 17 `java.net.http.HttpClient.newWebSocketBuilder()` as a builder that binds URI, per-attempt headers supplier, `WsRpcChannel`, and executor up front, exposing a reactive listener that forwards text into `WsRpcChannel.onTextMessage` and binary into `WsRpcChannel.onBinaryMessage` so `sendStream` works without ad-hoc wiring, preserving `start`/chunks/`done` emission order through the single send queue. No custom binary hook exists. The legacy `connectTo(uri, bearerToken, channel)` SHALL remain as a thin shim delegating to the builder with a single `Authorization: Bearer <token>` header.

#### Scenario: Outbound connection with Bearer header
- **WHEN** `JdkWsClient` connects via the legacy shim with a target URI and an optional access token
- **THEN** it issues a WebSocket upgrade request including the `Authorization: Bearer <token>` header if configured

#### Scenario: Outbound connection with supplied headers
- **WHEN** `JdkWsClient` connects via the builder with a headers supplier
- **THEN** it invokes the supplier on every handshake attempt and sends the returned headers (e.g. raw `Authorization` JWT plus `X-SERVER-ID`) on the upgrade request, sending no `Authorization` header when the supplier omits it

#### Scenario: Non-WS scheme rejected
- **WHEN** the builder is given a URI whose scheme is not `ws` or `wss`
- **THEN** it fails fast with an `IllegalArgumentException` instead of dialing

#### Scenario: Inbound binary always reaches the channel
- **WHEN** a binary frame arrives
- **THEN** it is ingested by `WsRpcChannel.onBinaryMessage` for stream reassembly, and non-stream frames are dropped with a log without failing the connection or pending RPCs

#### Scenario: Stream frame order preserved
- **WHEN** the channel enqueues `start` text, binary chunks, then `done` text
- **THEN** `JdkWsClient` transmits them in that order and inbound binary is forwarded to the channel in arrival order

### Requirement: Ping-Pong Keepalive and Auto-Reconnection

`JdkWsClient` SHALL automatically schedule ping frames at a configured interval (default 20 seconds) to maintain connection liveness, detect timeout disconnections after a configured pong deadline (default 45 seconds), and execute an exponential backoff reconnect loop (1s to 5s) on socket termination. Reconnect attempts SHALL re-invoke the headers supplier so rotated credentials are picked up.

#### Scenario: Periodic ping delivery

- **WHEN** the connection is open
- **THEN** `JdkWsClient` automatically sends a WebSocket ping frame every configured interval (default 20 seconds)

#### Scenario: Automatic reconnect attempt

- **WHEN** the remote server drops the connection
- **THEN** `JdkWsClient` initiates a scheduled reconnect attempt using exponential backoff with jitter and re-resolved headers
