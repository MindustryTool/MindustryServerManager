## MODIFIED Requirements

### Requirement: Client listens to an event stream with parameters
The system SHALL provide a `listen(event, data, handler)` method that sends a `listen` frame with the given event name and `data` payload, registers the handler for incoming events, and returns a `CompletableFuture<Void>` that completes when the `listening` acknowledgement is received or fails when a `listen-error` is received.

#### Scenario: Successful listen with typed parameters
- **WHEN** client calls `listen("usage", UsageParams("srv-123"), handler)`
- **THEN** a `listen` frame is sent with `event="usage"` and `payload={"data":{"serverId":"srv-123"}}`
- **THEN** the returned future completes when a `listening` frame with `responseOf=listenId` arrives

#### Scenario: Listen rejected by publisher
- **WHEN** the publisher responds with a `listen-error` frame for the `listen` request
- **THEN** the returned future completes exceptionally with the error payload

#### Scenario: Listen waits for open session
- **WHEN** `listen` is called with no open WebSocket session
- **THEN** the call suspends and transmits once a session opens within the session-wait limit
- **THEN** if no session opens within the limit, the future fails with `TimeoutException`

### Requirement: Client unlistens by request ID
The system SHALL provide an `unlisten(requestId)` method that sends an `unlisten` frame with `responseOf=requestId` and optional `reason` payload, removes the local handler, and treats the stream as ended when the publisher's `listen-ended` frame arrives.

#### Scenario: Unlisten removes handler and notifies publisher
- **WHEN** client calls `unlisten(listenId)` with reason "client cancel"
- **THEN** an `unlisten` frame is sent with `responseOf=listenId` and `payload={"reason":"client cancel"}`
- **THEN** the local handler for `listenId` is removed
- **THEN** a `listen-ended` frame with `responseOf=listenId` is expected and marks the stream ended

#### Scenario: Unlisten for unknown ID is no-op
- **WHEN** client calls `unlisten(unknownId)`
- **THEN** no frame is sent and no error occurs

### Requirement: Client receives events via registered handler
The system SHALL route incoming `event` frames (with `event=eventName`, `responseOf=listenerId`) to the handler registered for that `listenerId`.

#### Scenario: Event delivered to correct handler
- **WHEN** an `event` frame arrives with `responseOf=listenId` and `event="usage"`
- **THEN** the handler registered for `listenId` is invoked with the event payload

#### Scenario: Event for unknown listener dropped
- **WHEN** an `event` frame arrives with `responseOf` not matching any active listener
- **THEN** the frame is dropped with a log and no handler is invoked

#### Scenario: Publisher-initiated termination
- **WHEN** a `listen-ended` or `listen-error` frame arrives with `responseOf=listenId`
- **THEN** the stream is marked ended
- **THEN** the handler is removed
- **THEN** any pending `listen` future is failed if not already completed
- **THEN** `onClose` callbacks are invoked

### Requirement: Multiple independent listeners to the same event name
The system SHALL allow multiple concurrent `listen` requests to the same event name with different `data` parameters, each with independent handlers and lifecycles.

#### Scenario: Two listeners to same event with different params
- **WHEN** client calls `listen("usage", {"serverId":"srv-1"}, h1)` and `listen("usage", {"serverId":"srv-2"}, h2)`
- **THEN** two separate `listen` frames are sent with different `data`
- **THEN** two independent futures are returned
- **THEN** events for `srv-1` route to `h1`, events for `srv-2` route to `h2`

### Requirement: Connection close terminates all event streams
The system SHALL clean up all active listeners on connection close, invoke their `onClose` callbacks, and fail any pending `listen` futures with the close cause.

#### Scenario: Close cleans up listeners
- **WHEN** `onClose(cause)` is called with active listeners
- **THEN** all listener handlers are removed
- **THEN** all `onClose` callbacks are invoked
- **THEN** pending `listen` futures complete exceptionally with the close cause
