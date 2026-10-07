# jdk-websocket-client

## Purpose

TBD - created by syncing change extract-gateway-subproject. Update Purpose after implementation.
## Requirements
### Requirement: Standard JDK 17 WebSocket Client
The `:gateway` module SHALL provide `WsClient` wrapping Java 17 `java.net.http.HttpClient.newWebSocketBuilder()` as a builder that binds URI, per-attempt headers supplier, `RpcChannel`, and executor up front, exposing a reactive listener that forwards text into `RpcChannel.onTextMessage` and binary into `RpcChannel.onBinaryMessage` so `sendStream` works without ad-hoc wiring, preserving `start`/chunks/`done` emission order through the single send queue. No custom binary hook exists.

#### Scenario: Outbound connection with supplied headers
- **WHEN** `WsClient` connects via the builder with a headers supplier
- **THEN** it invokes the supplier on every handshake attempt and sends the returned headers (e.g. raw `Authorization` JWT plus `X-SERVER-ID`) on the upgrade request, sending no `Authorization` header when the supplier omits it

#### Scenario: Non-WS scheme rejected
- **WHEN** the builder is given a URI whose scheme is not `ws` or `wss`
- **THEN** it fails fast with an `IllegalArgumentException` instead of dialing

#### Scenario: Inbound binary always reaches the channel
- **WHEN** a binary frame arrives
- **THEN** it is ingested by `RpcChannel.onBinaryMessage` for stream reassembly, and non-stream frames are dropped with a log without failing the connection or pending RPCs

#### Scenario: Stream frame order preserved
- **WHEN** the channel enqueues `start` text, binary chunks, then `done` text
- **THEN** `WsClient` transmits them in that order and inbound binary is forwarded to the channel in arrival order

### Requirement: Ping-Pong Keepalive and Auto-Reconnection

`WsClient` SHALL automatically schedule ping frames at a configured interval (default 20 seconds) to maintain connection liveness, detect timeout disconnections after a configured pong deadline (default 45 seconds), and execute an exponential backoff reconnect loop (1s to 60s) on socket termination, except inbound close code `4234` (kick) which SHALL skip auto-reconnect while allowing a later manual `connect()` to dial immediately. Every connect SHALL use a fresh transport. Reconnect attempts SHALL re-invoke the headers supplier so rotated credentials are picked up.

#### Scenario: Periodic ping delivery

- **WHEN** the connection is open
- **THEN** `WsClient` automatically sends a WebSocket ping frame every configured interval (default 20 seconds)

#### Scenario: Automatic reconnect attempt

- **WHEN** the remote server drops the connection with a code other than `4234`
- **THEN** `WsClient` initiates a scheduled reconnect attempt using exponential backoff capped at 60 seconds with jitter and re-resolved headers

#### Scenario: Fresh transport per connect

- **WHEN** a connect attempt starts
- **THEN** it creates a new transport so channel overwrite can distinguish old from new and never self-kicks

#### Scenario: Kick with 4234 stops auto-reconnect

- **WHEN** the current socket closes with code `4234`
- **THEN** no reconnect is scheduled, a warn log records uri plus code, pending work fails with the kick cause, and a later manual `connect()` clears the kick and dials immediately

#### Scenario: Stale-socket close ignored

- **WHEN** a close or kick arrives for a superseded transport
- **THEN** it is dropped and the live transport plus its reconnect policy are unaffected

### Requirement: Adopt session before granting reads

`WsClient` SHALL NOT enable inbound delivery until the channel has adopted the transport as its session. `WebSocketConnection.attach` SHALL bind the socket and start the sender and ping without requesting frames; a single `WebSocketConnection.beginRead()` SHALL grant the first read; `WsClient` SHALL call `RpcChannel.onOpen(transport)` before `beginRead()`. The `WebSocket.Listener.onOpen` override SHALL remain and SHALL NOT request a frame, because the JDK default `onOpen` requests one automatically.

#### Scenario: No frame delivered before adoption

- **WHEN** the server sends a frame immediately after the handshake completes and `WsClient` has not yet called `rpcChannel.onOpen`
- **THEN** the frame is not delivered, because no read has been requested yet

#### Scenario: Reply flows once adopted

- **WHEN** the channel adopts the transport and only then `beginRead()` is called
- **THEN** inbound frames are delivered and a request answered in the same window resolves normally instead of logging `no open session`

#### Scenario: Listener onOpen does not self-request

- **WHEN** the JDK invokes `WebSocket.Listener.onOpen`
- **THEN** no `request(n)` is issued by the listener, so the default JDK auto-request cannot open a pre-adoption window

#### Scenario: Attach without read

- **WHEN** a fresh transport is attached but the connect is superseded before `beginRead()`
- **THEN** the transport never requests frames and terminates without delivering inbound to the channel

