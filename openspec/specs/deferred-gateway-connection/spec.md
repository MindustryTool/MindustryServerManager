# deferred-gateway-connection

## Purpose

TBD - created by syncing change optimize-startup-performance. Update Purpose after implementation.

## Requirements

### Requirement: Gateway connection never blocks startup
The `ApiGateway` component SHALL NOT perform a blocking WebSocket connection during its `@Init` or during component creation. Plugin load SHALL complete without waiting for the DNS resolution, socket creation, or connection handshake to the server-manager gateway.

#### Scenario: Load completes while gateway connects
- **WHEN** the plugin initializes
- **THEN** `Registry.init` completes without blocking on the gateway connection
- **AND** the gateway connection is established asynchronously in the background

#### Scenario: Connection eventually succeeds
- **WHEN** the background gateway connection establishes successfully
- **THEN** the `WsHandler.onConnected` path logs "Connected to server manager"
- **AND** message handlers registered during `init` are usable from that point on

#### Scenario: Connection failure triggers retry
- **WHEN** the background gateway connection fails or the socket closes while not shutting down
- **THEN** the existing reconnect scheduling retries the connection without blocking plugin load

### Requirement: Gateway connection lifecycle is guarded
The asynchronous connection attempt SHALL be guarded against duplicates and SHALL NOT be started once the gateway is shut down. Plugin unload SHALL NOT tear down an established connection.

#### Scenario: No duplicate concurrent connects
- **WHEN** a connection attempt is already in progress
- **THEN** a second `connect()` invocation does not start a second concurrent attempt

#### Scenario: No connect after shutdown
- **WHEN** the gateway shutdown flag is set
- **THEN** `connectAsync`/`connect` do nothing
- **AND** the reconnect scheduling stops

#### Scenario: Unload preserves connection
- **WHEN** the plugin is destroyed
- **THEN** the websocket is not disconnected by destroy
- **AND** the connection lifecycle continues to be managed by the existing reconnect logic