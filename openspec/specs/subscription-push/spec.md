# subscription-push

## Purpose

Server-side subscription handling: typed handler registration, per-subscription initialization, and the PushHandle event and close contract. Synced from change add-subscription-streams.

## Requirements

### Requirement: Server registers subscription handler with typed parameters
The system SHALL provide `registerSubscriptionHandler(eventType, paramsClass, onSubscribe)` where `onSubscribe` is a function receiving deserialized `data` payload and returning `CompletableFuture<PushHandle>`.

#### Scenario: Handler registration succeeds
- **WHEN** server calls `registerSubscriptionHandler("usage", UsageParams.class, params -> ...)`
- **THEN** subsequent `subscribe` frames with `eventType="usage"` are routed to this handler

#### Scenario: Duplicate registration throws
- **WHEN** server registers two handlers for the same `eventType`
- **THEN** the second registration throws `IllegalArgumentException`

#### Scenario: Reserved type rejected
- **WHEN** server registers handler with type `"subscribe"` or `"unsubscribe"`
- **THEN** the call throws `IllegalArgumentException`

### Requirement: onSubscribe called once per subscription with lazy initialization
The system SHALL invoke `onSubscribe(params)` exactly once per incoming `subscribe` request, passing the deserialized `data` payload, and await the returned `PushHandle` future before sending events.

#### Scenario: onSubscribe invoked per subscription
- **WHEN** two `subscribe` requests arrive for `eventType="usage"` with different `data`
- **THEN** `onSubscribe` is called twice, once per subscription

#### Scenario: onSubscribe async initialization supported
- **WHEN** `onSubscribe` returns a `CompletableFuture<PushHandle>` that completes later
- **THEN** the subscription acknowledgment (first event) waits for the future to complete
- **THEN** if the future fails, the subscription is rejected with an error frame

#### Scenario: onSubscribe failure rejects subscription
- **WHEN** `onSubscribe` future completes exceptionally
- **THEN** server sends an error frame with `error=true`, `responseOf=subscribeId`, and the failure reason
- **THEN** no `PushHandle` is created for that subscription

### Requirement: PushHandle controls per-subscriber event emission
The system SHALL provide a `PushHandle` interface with methods to push events, complete cleanly, fail with error, check closure, and register cleanup callbacks.

#### Scenario: PushHandle.push sends event frame
- **WHEN** server calls `handle.push(eventObject)`
- **THEN** an event frame is sent with `type=eventType`, `responseOf=subscriptionId`, fresh `id`, `payload=eventObject`, `error=false`

#### Scenario: PushHandle.complete ends subscription cleanly
- **WHEN** server calls `handle.complete()`
- **THEN** the subscription is marked closed locally
- **THEN** no frame is sent (clean end per WS-RPC 13.6 uses error frame; complete = stop pushing)

#### Scenario: PushHandle.fail sends error frame and ends subscription
- **WHEN** server calls `handle.fail("server not found")`
- **THEN** an error frame is sent with `type=eventType`, `responseOf=subscriptionId`, `error=true`, `payload="server not found"`
- **THEN** the subscription is marked closed

#### Scenario: PushHandle.isClosed reflects client unsubscribe
- **WHEN** client sends `unsubscribe` for this subscription
- **THEN** `handle.isClosed()` returns `true`
- **THEN** further `push` calls are no-ops or throw

#### Scenario: PushHandle.isClosed reflects connection loss
- **WHEN** `onClose` is called on the channel
- **THEN** `handle.isClosed()` returns `true` for all active subscriptions

#### Scenario: PushHandle.onClose registers cleanup callback
- **WHEN** server calls `handle.onClose(() -> cleanup())`
- **THEN** `cleanup()` is invoked when subscription ends (client unsubscribe, server fail/complete, or connection close)

#### Scenario: onClose callback invoked on client unsubscribe
- **WHEN** client sends `unsubscribe` frame
- **THEN** the registered `onClose` callback is invoked

#### Scenario: onClose callback invoked on server fail
- **WHEN** server calls `handle.fail(reason)`
- **THEN** the registered `onClose` callback is invoked

#### Scenario: onClose callback invoked on connection close
- **WHEN** channel `onClose` is called
- **THEN** all active subscriptions' `onClose` callbacks are invoked

### Requirement: Last subscriber cleanup for lazy resources
The system SHALL support tracking subscriber count per `eventType` and invoking a cleanup when the last subscriber unsubscribes.

#### Scenario: onSubscribe receives context with subscriber count
- **WHEN** first subscriber subscribes to `eventType`
- **THEN** `onSubscribe` can detect it's the first subscriber

#### Scenario: Cleanup when last subscriber leaves
- **WHEN** last subscriber unsubscribes or connection closes
- **THEN** server can clean up resources (e.g., cancel scheduled tasks)
