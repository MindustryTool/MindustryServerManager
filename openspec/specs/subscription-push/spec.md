# subscription-push

## Purpose

Server-side event-stream handling: typed listener registration, per-stream initialization, and the SubscriptionHandle event and close contract. Synced from change ws-rpc-v2.

## Requirements

### Requirement: Server registers event-stream handler with typed parameters
The system SHALL provide `registerEventListener(event, paramsClass, onListen)` where `onListen` is a function receiving the deserialized `data` payload and returning `CompletableFuture<SubscriptionHandle>`. Event names SHALL NOT be reserved; any name, including one equal to a frame kind, SHALL be accepted.

#### Scenario: Handler registration succeeds
- **WHEN** server calls `registerEventListener("usage", UsageParams.class, params -> ...)`
- **THEN** subsequent `listen` frames with `event="usage"` are routed to this handler

#### Scenario: Duplicate registration throws
- **WHEN** server registers two handlers for the same event name
- **THEN** the second registration throws `IllegalArgumentException`

#### Scenario: Event name equal to a kind accepted
- **WHEN** server registers a handler for an event name such as `"event"` or `"listen"`
- **THEN** registration succeeds and matching `listen` frames dispatch to it

### Requirement: onListen called once per event stream with lazy initialization
The system SHALL invoke `onListen(params)` exactly once per incoming `listen` request, passing the deserialized `data` payload, and SHALL await the returned `SubscriptionHandle` future before sending the `listening` acknowledgement and any events.

#### Scenario: onListen invoked per event stream
- **WHEN** two `listen` requests arrive for `event="usage"` with different `data`
- **THEN** `onListen` is called twice, once per stream

#### Scenario: onListen async initialization supported
- **WHEN** `onListen` returns a `CompletableFuture<SubscriptionHandle>` that completes later
- **THEN** the `listening` acknowledgement waits for the future to complete
- **THEN** if the future fails, the stream is rejected with a `listen-error` frame

#### Scenario: onListen failure rejects listen
- **WHEN** the `onListen` future completes exceptionally
- **THEN** server sends a `listen-error` frame with `responseOf=listenId` and the failure reason
- **THEN** no `SubscriptionHandle` is created for that event stream

### Requirement: SubscriptionHandle controls per-subscriber event emission
The system SHALL provide a `SubscriptionHandle` interface with methods to push events, complete cleanly, fail with error, check closure, and register cleanup callbacks.

#### Scenario: SubscriptionHandle.push sends event frame
- **WHEN** server calls `handle.push(eventObject)`
- **THEN** an `event` frame is sent with `kind="event"`, `event=eventName`, `responseOf=listenerId`, fresh `id`, and `payload=eventObject`

#### Scenario: SubscriptionHandle.complete ends stream cleanly
- **WHEN** server calls `handle.complete()`
- **THEN** a `listen-ended` frame is sent with `event=eventName` and `responseOf=listenerId`
- **THEN** the stream is marked closed locally

#### Scenario: SubscriptionHandle.fail sends listen-error and ends stream
- **WHEN** server calls `handle.fail("server not found")`
- **THEN** a `listen-error` frame is sent with `event=eventName`, `responseOf=listenerId`, and `payload="server not found"`
- **THEN** the stream is marked closed

#### Scenario: SubscriptionHandle.isClosed reflects client unlisten
- **WHEN** client sends `unlisten` for this event stream
- **THEN** `handle.isClosed()` returns `true`
- **THEN** further `push` calls are no-ops or throw

#### Scenario: SubscriptionHandle.isClosed reflects connection loss
- **WHEN** `onClose` is called on the channel
- **THEN** `handle.isClosed()` returns `true` for all active event streams

#### Scenario: SubscriptionHandle.onClose registers cleanup callback
- **WHEN** server calls `handle.onClose(() -> cleanup())`
- **THEN** `cleanup()` is invoked when the stream ends (client unlisten, server complete/fail, or connection close)

#### Scenario: onClose callback invoked on client unlisten
- **WHEN** client sends an `unlisten` frame
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
