# jdk-websocket-client

## Purpose

TBD - created by syncing change extract-gateway-subproject. Update Purpose after implementation.

## Requirements

### Requirement: Standard JDK 17 WebSocket Client
The `:gateway` module SHALL provide `JdkWsClient` wrapping Java 17 `java.net.http.HttpClient.newWebSocketBuilder()` as a builder that binds URI, per-attempt headers supplier, `WsRpcChannel`, and executor up front, exposing a reactive listener that forwards text into `WsRpcChannel.onTextMessage` and binary into `WsRpcChannel.onBinaryMessage` so `sendStream` works without ad-hoc wiring, preserving `start`/chunks/`done` emission order through the single send queue. No custom binary hook exists.

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

`JdkWsClient` SHALL automatically schedule ping frames at a configured interval (default 20 seconds) to maintain connection liveness, detect timeout disconnections after a configured pong deadline (default 45 seconds), and execute an exponential backoff reconnect loop (1s to 60s) on socket termination, except inbound close code `4234` (kick) which SHALL skip auto-reconnect while allowing a later manual `connect()` to dial immediately. Every connect SHALL use a fresh transport. Reconnect attempts SHALL re-invoke the headers supplier so rotated credentials are picked up.

#### Scenario: Periodic ping delivery

- **WHEN** the connection is open
- **THEN** `JdkWsClient` automatically sends a WebSocket ping frame every configured interval (default 20 seconds)

#### Scenario: Automatic reconnect attempt

- **WHEN** the remote server drops the connection with a code other than `4234`
- **THEN** `JdkWsClient` initiates a scheduled reconnect attempt using exponential backoff capped at 60 seconds with jitter and re-resolved headers

#### Scenario: Fresh transport per connect

- **WHEN** a connect attempt starts
- **THEN** it creates a new transport so channel overwrite can distinguish old from new and never self-kicks

#### Scenario: Kick with 4234 stops auto-reconnect

- **WHEN** the current socket closes with code `4234`
- **THEN** no reconnect is scheduled, a warn log records uri plus code, pending work fails with the kick cause, and a later manual `connect()` clears the kick and dials immediately

#### Scenario: Stale-socket close ignored

- **WHEN** a close or kick arrives for a superseded transport
- **THEN** it is dropped and the live transport plus its reconnect policy are unaffected
