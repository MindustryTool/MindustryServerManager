## ADDED Requirements

### Requirement: HTTP hosting confined to the server module
All client-facing graph endpoints (REST and SSE) SHALL be hosted exclusively by the `server` module; the plugin module SHALL NOT bind ports or serve HTTP. The server module forwards graph operations to the plugin over the existing outbound gateway WS RPC (`WsMessage`) protocol.

#### Scenario: Plugin binds no ports
- **WHEN** the plugin initializes with the graph subsystem enabled
- **THEN** no additional listening socket exists beyond pre-existing infrastructure

#### Scenario: Request path traverses gateway
- **WHEN** a client calls `GET /api/v2/graph/functions`
- **THEN** the server module authenticates the request, forwards an RPC to the plugin via the gateway, and returns the plugin's response payload

### Requirement: Registry discovery endpoints
The server module SHALL expose registry discovery: paged fuzzy search (`GET /api/v2/graph/functions?query&category&ownerType&compatibleWith&limit`), function detail (`GET /api/v2/graph/functions/{id}`), event listing, type listing — each response carrying the current `registryFingerprint` and honoring conditional requests.

#### Scenario: Conditional revalidation
- **WHEN** a repeat request arrives with a fingerprint header matching current state
- **THEN** the endpoint responds 304 and the client keeps its cached page

### Requirement: Graph document CRUD backed by SQLite
Graph documents SHALL be created, read, updated, listed, and deleted through `GET/POST/PUT/DELETE /api/v2/graphs[/{id}]`; documents persist as JSON rows in the plugin database (authoritative store), updates enforce optimistic revision checks, and no filesystem storage of graph JSON is required.

#### Scenario: Stale revision update rejected
- **WHEN** a PUT arrives with a revision older than stored
- **THEN** the update is rejected with a conflict diagnostic containing the current revision

### Requirement: Lifecycle actions with immediate effect
The API SHALL expose `POST .../validate`, `/compile`, `/enable`, `/disable`, `/remove` and `GET .../status`, each taking effect at runtime without restart: enable lazily compiles and activates event bridges; disable/remove cancels live executions deterministically and releases bindings per the execution lifecycle spec.

#### Scenario: Enable then status reflects activation
- **WHEN** validate and compile succeed and `/enable` is called on a stored graph during live traffic
- **THEN** subsequent triggers execute and `status` reports the loaded generation

#### Scenario: Remove takes effect immediately
- **WHEN** `/remove` is called for an enabled graph
- **THEN** it stops receiving events immediately and disappears from graph listings without restart

### Requirement: SSE streams for realtime telemetry
Realtime graph telemetry SHALL use Server-Sent Events hosted by the server module — at minimum `GET /api/v2/graphs/{id}/debug/stream` (debug states/traces/inspection) and `GET /api/v2/graphs/events` (engine-wide lifecycle/status events) — with heartbeats and clean client-disconnect handling; WebSocket SHALL NOT be used for these client-facing streams.

#### Scenario: Lifecycle event broadcast
- **WHEN** a graph is enabled then removed
- **THEN** subscribers of the engine-events SSE stream receive corresponding lifecycle events in order

#### Scenario: Disconnected debug client cleaned up
- **WHEN** a debug SSE client disconnects
- **THEN** its debugger attachment releases within bounded time and breakpoints stop evaluating
