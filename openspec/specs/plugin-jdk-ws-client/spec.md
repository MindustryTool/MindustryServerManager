# plugin-jdk-ws-client

## Purpose

TBD - created by syncing change migrate-plugin-to-jdk-ws. Update Purpose after implementation.

## Requirements

### Requirement: Southbound plugin client on JdkWsClient

The plugin SHALL connect its southbound `/gateway` socket through `JdkWsClient` built with a per-attempt headers supplier, a per-attempt gateway URI supplier following the `plugin-gateway-url` resolution rule, a shared IO executor for its `WsRpcChannel`, and no `nv-websocket-client` runtime dependency.

#### Scenario: Handshake headers per attempt

- **WHEN** the plugin initiates or retries a southbound handshake
- **THEN** it resolves `Authorization` (raw JWT from `server.json`) and `X-SERVER-ID` (env) fresh via the supplier and sends both headers on the upgrade request

#### Scenario: Missing JWT dials without auth

- **WHEN** `server.json` is absent or contains no JWT at attempt time
- **THEN** the plugin dials without the `Authorization` header so the manager can provision via its `Token expired` → `server.json` rewrite, and the next attempt re-reads the fresh JWT

#### Scenario: ws scheme endpoint

- **WHEN** the plugin builds its gateway URI
- **THEN** the URI comes from the `PLUGIN_GATEWAY_URL` resolution rule (explicit env or dev/prod default), uses the `ws://` (or `wss://`) scheme including the `/gateway` path, and `JdkWsClient` rejects non-`ws`/`wss` URIs instead of dialing

#### Scenario: Shared IO channel executor

- **WHEN** southbound text frames arrive
- **THEN** `WsRpcChannel` dispatches on the shared IO pool, never inline on the JDK listener thread and never on the ping/reconnect scheduler

### Requirement: Protocol-ping-only southbound liveness

The plugin SHALL rely on `JdkWsClient` protocol ping, pong-deadline, and exponential-backoff reconnect as its only keepalive, with state updates sent on events rather than on a fixed heartbeat schedule.

#### Scenario: No fixed app heartbeat

- **WHEN** the southbound socket is idle with no game events
- **THEN** the plugin sends no periodic `sendStateUpdate` heartbeat and performs no `@Schedule` reconnect polling

#### Scenario: Event-driven state updates

- **WHEN** a session, world-state, or play event fires
- **THEN** the plugin sends its state update over the open southbound channel

#### Scenario: Silent drop reconnects with backoff

- **WHEN** the pong deadline expires or the socket drops
- **THEN** the plugin fails pending RPCs via `onClose` and reconnects with exponential backoff (1s to 30s plus jitter), re-resolving headers on the next attempt

### Requirement: No inbound binary and no nv dependency

The plugin SHALL ignore inbound binary frames on southbound and SHALL NOT ship `nv-websocket-client` in `plugin.jar`.

#### Scenario: Binary frame ignored

- **WHEN** a binary frame arrives on the southbound socket
- **THEN** the client drops it without failing the connection or pending RPCs

#### Scenario: nv dependency absent

- **WHEN** `:plugin:jar` is built
- **THEN** no `com.neovisionaries` classes are present in the fat jar and no `ApiGateway` code path references them
