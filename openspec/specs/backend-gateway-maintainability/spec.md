# backend-gateway-maintainability

## Purpose

Structural contract for the backend gateway (factory-based construction, protocol-seam class split, single config record). Behavior-preserving; captures the refactor's invariants as testable requirements. Synced from change tidy-backend-gateway.

## Requirements

### Requirement: Factory-Based Gateway Construction
The system SHALL construct `:gateway` clients and channels through explicit factory methods that require all mandatory state up front, with no nullable constructor parameters and no required setter sequencing before use.

#### Scenario: Channel creation without nulls
- **WHEN** server code creates a `RpcChannel` or `WsClient`
- **THEN** no call site passes `null` for mapper, scheduler, executor, or session dependencies

#### Scenario: Fully-formed client before connect
- **WHEN** a `WsClient` initiates `connect()`
- **THEN** its RPC channel and binary handler are already bound at construction time, so no message can be dropped due to mis-ordered setters

### Requirement: Protocol-Seam Class Split
The server-side backend gateway SHALL be organized as exactly two classes split along the wire-protocol seam: `BackendGateway` for connection lifecycle, `sync-state`, and event bridging, and `BackendRpc` for the RPC handler table plus binary file transfers.

#### Scenario: Lifecycle without bytes
- **WHEN** the backend connection opens, closes, or drops
- **THEN** all handling lives in `BackendGateway`, which never touches binary chunk buffers

#### Scenario: Single home for backend commands
- **WHEN** a new backend RPC command or transfer flow is added
- **THEN** it is registered in `BackendRpc` without touching connection lifecycle code

### Requirement: Single Gateway Config Record
The system SHALL resolve `BACKEND_WS_URL` and `ACCESS_TOKEN_v2` exactly once into a `BackendGatewayConfig` record in `ServerMain`, and backend gateway services SHALL take that record instead of reading environment configuration themselves.

#### Scenario: Missing configuration suspends cleanly
- **WHEN** `ServerMain` resolves a config with a blank URL or token
- **THEN** the backend connection is suspended with a fatal log and no connection attempts are made

#### Scenario: Config is unit-testable
- **WHEN** tests construct a `BackendGatewayConfig` directly
- **THEN** no environment variables or static env mocking are required

### Requirement: Per-Operation Request DTOs
Each backend RPC operation SHALL deserialize into its own request class shaped exactly like its payload (one class per operation, nested near the handlers in `BackendRpc`), and each class SHALL reject missing required fields during Jackson deserialization with an error naming the concrete class.

#### Scenario: Operation-shaped deserialization
- **WHEN** a backend command frame arrives for any registered operation
- **THEN** its payload deserializes into that operation's dedicated request class, never into a shared composite

#### Scenario: Missing field fails fast
- **WHEN** a payload omits a required field for its operation
- **THEN** deserialization fails with an error naming the concrete request class instead of failing later inside the handler
