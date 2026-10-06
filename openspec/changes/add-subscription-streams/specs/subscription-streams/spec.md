## ADDED Requirements

### Requirement: Client subscribes to event type with parameters
The system SHALL provide a `subscribe(eventType, data, handler)` method that sends a `subscribe` frame with the given `eventType` and `data` payload, registers the handler for incoming events, and returns a `CompletableFuture<Void>` that completes when the first event (acknowledgment) is received or fails if the subscription is rejected.

#### Scenario: Successful subscription with typed parameters
- **WHEN** client calls `subscribe("usage", UsageParams("srv-123"), handler)`
- **THEN** a `subscribe` frame is sent with `eventType="usage"` and `data={"serverId":"srv-123"}`
- **THEN** the returned future completes when an event frame with `responseOf=subscribeId` arrives

#### Scenario: Subscription rejected by server
- **WHEN** server responds with `error=true` frame for the subscribe request
- **THEN** the returned future completes exceptionally with the error payload

#### Scenario: Subscription waits for open session
- **WHEN** `subscribe` is called with no open WebSocket session
- **THEN** the call suspends and transmits once a session opens within the session-wait limit
- **THEN** if no session opens within the limit, the future fails with `TimeoutException`

### Requirement: Client unsubscribes by request ID
The system SHALL provide an `unsubscribe(requestId)` method that sends an `unsubscribe` frame with `responseOf=requestId` and optional `reason` payload, and removes the local subscription handler.

#### Scenario: Unsubscribe removes handler and notifies server
- **WHEN** client calls `unsubscribe(subscribeId)` with reason "client cancel"
- **THEN** an `unsubscribe` frame is sent with `responseOf=subscribeId` and `payload={"reason":"client cancel"}`
- **THEN** the local handler for `subscribeId` is removed
- **THEN** no reply is expected from server (fire-and-forget per WS-RPC 13.5)

#### Scenario: Unsubscribe for unknown ID is no-op
- **WHEN** client calls `unsubscribe(unknownId)`
- **THEN** no frame is sent and no error occurs

### Requirement: Client receives events via registered handler
The system SHALL route incoming event frames (with `type=eventType`, `responseOf=subscriptionId`, `error=false`) to the handler registered for that `subscriptionId`.

#### Scenario: Event delivered to correct handler
- **WHEN** an event frame arrives with `responseOf=subscribeId` and `type="usage"`
- **THEN** the handler registered for `subscribeId` is invoked with the event payload

#### Scenario: Event for unknown subscription dropped
- **WHEN** an event frame arrives with `responseOf` not matching any active subscription
- **THEN** the frame is dropped with a log and no handler is invoked

#### Scenario: Server-initiated termination via error frame
- **WHEN** an error frame arrives with `responseOf=subscribeId`, `type="usage"`, `error=true`
- **THEN** the subscription is marked closed
- **THEN** the handler is removed
- **THEN** any pending `subscribe` future is failed (if not already completed)
- **THEN** `onClose` callbacks are invoked

### Requirement: Multiple independent subscriptions to same event type
The system SHALL allow multiple concurrent subscriptions to the same `eventType` with different `data` parameters, each with independent handlers and lifecycles.

#### Scenario: Two subscriptions to same event type with different params
- **WHEN** client calls `subscribe("usage", {"serverId":"srv-1"}, h1)` and `subscribe("usage", {"serverId":"srv-2"}, h2)`
- **THEN** two separate `subscribe` frames are sent with different `data`
- **THEN** two independent futures are returned
- **THEN** events for `srv-1` route to `h1`, events for `srv-2` route to `h2`

### Requirement: Connection close terminates all subscriptions
The system SHALL clean up all active subscriptions on connection close, invoke their `onClose` callbacks, and fail any pending `subscribe` futures with the close cause.

#### Scenario: Close cleans up subscriptions
- **WHEN** `onClose(cause)` is called with active subscriptions
- **THEN** all subscription handlers are removed
- **THEN** all `onClose` callbacks are invoked
- **THEN** pending `subscribe` futures complete exceptionally with the close cause