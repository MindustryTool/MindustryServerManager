# jdk-websocket-client

## Purpose

TBD - created by syncing change extract-gateway-subproject. Update Purpose after implementation.

## Requirements

### Requirement: Standard JDK 17 WebSocket Client
The `:gateway` module SHALL provide `JdkWsClient` wrapping Java 17 `java.net.http.HttpClient.newWebSocketBuilder()`, exposing a reactive listener that forwards text and binary messages into `WsRpcChannel`.

#### Scenario: Outbound connection with Bearer header
- **WHEN** `JdkWsClient.connect()` is called with a target URI and an optional access token
- **THEN** it issues a WebSocket upgrade request including the `Authorization: Bearer <token>` header if configured

### Requirement: Ping-Pong Keepalive and Auto-Reconnection
`JdkWsClient` SHALL automatically schedule ping frames at 20-second intervals to maintain connection liveness, detect timeout disconnections after 45 seconds, and execute an exponential backoff reconnect loop (1s to 30s) on socket termination.

#### Scenario: Periodic ping delivery
- **WHEN** the connection is open
- **THEN** `JdkWsClient` automatically sends a WebSocket ping frame every 20 seconds

#### Scenario: Automatic reconnect attempt
- **WHEN** the remote server drops the connection
- **THEN** `JdkWsClient` initiates a scheduled reconnect attempt using exponential backoff with jitter
