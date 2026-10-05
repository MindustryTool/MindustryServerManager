## ADDED Requirements

### Requirement: Outbound WSS Connection Handshake
The Server Manager SHALL initiate an outbound WebSocket connection over TLS (`wss://`) to the configured Backend API URL (`BACKEND_WS_URL`) during startup, transmitting the manager `accessToken` via the `Authorization: Bearer <accessToken>` handshake header or query parameter.

#### Scenario: Successful connection handshake
- **WHEN** the Server Manager boots with a valid `accessToken` and `BACKEND_WS_URL`
- **THEN** it successfully establishes a WebSocket connection with the central Backend API

#### Scenario: Missing configuration
- **WHEN** `BACKEND_WS_URL` or `accessToken` is not configured
- **THEN** the manager logs a fatal configuration error and suspends backend gateway connection attempts

### Requirement: Ping-Pong Heartbeat Maintenance
The Server Manager SHALL send a WebSocket ping frame or heartbeat message to the Backend API every 20 seconds to prevent reverse proxy and load balancer idle timeouts.

#### Scenario: Periodic heartbeat delivery
- **WHEN** the WSS connection is open and active
- **THEN** the manager transmits a heartbeat ping at 20-second fixed intervals

#### Scenario: Heartbeat timeout detection
- **WHEN** no response or pong frame is received within 45 seconds of a ping
- **THEN** the manager marks the connection as terminated and triggers the reconnection flow

### Requirement: Automatic Reconnection with Exponential Backoff
The Server Manager SHALL automatically attempt to re-establish the WSS connection whenever the socket disconnects, using exponential backoff starting at 1 second, doubling up to a maximum of 30 seconds, with randomized jitter applied.

#### Scenario: Remote disconnect reconnection
- **WHEN** the Backend API terminates the connection or the network drops
- **THEN** the manager schedules an automatic reconnect attempt with backoff delay without terminating running game servers

#### Scenario: Successful reconnect resets backoff
- **WHEN** a reconnect attempt succeeds after prior failures
- **THEN** the backoff delay resets back to the initial 1-second interval

### Requirement: State Synchronization on Connect
The Server Manager SHALL immediately transmit a `sync-state` message to the Backend API upon every successful connection or reconnection, containing the list of all active Mindustry server containers, their IDs, ports, and operational states.

#### Scenario: Sync state transmission after connection
- **WHEN** the WSS connection completes its handshake
- **THEN** the manager sends an inventory of all currently running and managed server containers to the backend
