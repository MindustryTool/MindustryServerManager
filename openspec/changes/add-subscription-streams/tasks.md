## 1. Channel Core: Reserved Types and Validation

- [x] 1.1 Add `SUBSCRIBE_TYPE = "subscribe"` and `UNSUBSCRIBE_TYPE = "unsubscribe"` constants to `WsRpcChannel`
- [x] 1.2 Update `rejectStreamControlType` to also reject `subscribe` and `unsubscribe` types for handler registration
- [x] 1.3 Add unit test verifying reserved types throw `IllegalArgumentException` on registration

## 2. Client API: Subscribe/Unsubscribe Methods

- [x] 2.1 Add `subscribe(String eventType, Object data, Consumer<JsonNode> handler)` method returning `CompletableFuture<Void>`
- [x] 2.2 Add `subscribe(String eventType, Object data, Consumer<JsonNode> handler, Duration timeout)` overload
- [x] 2.3 Add `unsubscribe(UUID requestId)` method
- [x] 2.4 Add `unsubscribe(UUID requestId, String reason)` overload
- [x] 2.5 Implement subscription ID tracking: `Map<UUID, SubscriptionSlot>` with client handler
- [x] 2.6 Implement `SubscriptionSlot` class with `eventType`, `handler`, `closed` flag, `onClose` callbacks
- [x] 2.7 Unit tests: successful subscribe, subscribe waits for session, subscribe timeout, unsubscribe, unsubscribe unknown ID

## 3. Server API: Subscription Handler Registration

- [x] 3.1 Define `PushHandle` interface with `push(Object)`, `complete()`, `fail(String)`, `isClosed()`, `onClose(Runnable)`
- [x] 3.2 Add `registerSubscriptionHandler(String eventType, Class<Params> paramsClass, Function<SubscriptionRequest<Params>, CompletableFuture<Void>> onSubscribe)`
- [x] 3.3 Add `unregisterSubscriptionHandler(String eventType)`
- [x] 3.4 Add `hasSubscriptionHandler(String eventType)`
- [x] 3.5 Implement server-side subscription tracking: `Map<UUID, ServerSubscriptionSlot>` with `PushHandle`
- [x] 3.6 Implement `ServerSubscriptionSlot` with `eventType`, `params`, `pushHandle`, `onClose` callbacks, `closed` flag
- [x] 3.7 Unit tests: handler registration, duplicate rejection, reserved type rejection

## 4. Wire Protocol: Frame Handling

- [x] 4.1 Handle inbound `subscribe` frames in `onTextMessage`:
  - Deserialize `eventType` and `data`
  - Look up server handler
  - Invoke `onSubscribe(params)` and await `PushHandle`
  - On success: store slot, send first event (ack) or empty event if no immediate event
  - On failure: send error frame with `error=true`
- [x] 4.2 Handle inbound `unsubscribe` frames:
  - Find slot by `responseOf`
  - Mark closed, invoke `onClose` callbacks, remove slot
  - No reply sent
- [x] 4.3 Handle event frame routing for client:
  - Match by `responseOf` to client subscription slot
  - Invoke registered handler with payload
  - Drop with log if no matching subscription
- [x] 4.4 Handle server-initiated error frames for client:
  - Match by `responseOf`
  - Mark subscription closed, invoke `onClose`, fail pending subscribe future
- [x] 4.5 Unit tests: subscribe frame handling, unsubscribe handling, event routing, error frame handling, unknown subscription dropped

## 5. PushHandle Implementation

- [x] 5.1 Implement `DefaultPushHandle` class wrapping channel, subscription ID, event type
- [x] 5.2 `push(Object event)`: serialize and send event frame with fresh ID, `responseOf=subscriptionId`, `type=eventType`
- [x] 5.3 `fail(String reason)`: send error frame with `error=true`, mark closed, invoke `onClose`
- [x] 5.4 `complete()`: mark closed, invoke `onClose` (no frame sent)
- [x] 5.5 `isClosed()`: check local closed flag
- [x] 5.6 `onClose(Runnable)`: register callback, invoke if already closed
- [x] 5.7 Unit tests: push sends frame, fail sends error frame, complete no frame, isClosed, onClose callbacks

## 6. Lifecycle and Cleanup

- [x] 6.1 On `onClose(cause)`: iterate all client subscriptions, fail pending futures, invoke `onClose` callbacks, clear map
- [x] 6.2 On `onClose(cause)`: iterate all server subscriptions, invoke `onClose` callbacks, clear map
- [x] 6.3 Ensure `shutdown()` cleans up subscriptions
- [x] 6.4 Unit tests: close during session wait fails subscribe, close cleans up client subscriptions, close cleans up server subscriptions

## 7. Integration Tests (WsRpcSubscriptionTest.java)

- [x] 7.1 Test: client subscribe → server onSubscribe → server push → client receives event
- [x] 7.2 Test: multiple subscriptions to same event type with different params
- [x] 7.3 Test: client unsubscribe → server cleanup → no more events
- [x] 7.4 Test: server fail → client receives error → subscription closed
- [x] 7.5 Test: server complete → client subscription closed
- [x] 7.6 Test: connection close → both sides cleanup
- [x] 7.7 Test: subscribe with no session → waits → succeeds on open
- [x] 7.8 Test: subscribe rejected by server (onSubscribe fails)
- [x] 7.9 Test: event for unknown subscription dropped
- [x] 7.10 Test: unsubscribe fire-and-forget (no reply)
- [x] 7.11 Test: reserved types rejected for handlers
- [x] 7.12 Test: onClose callbacks invoked on unsubscribe, fail, complete, connection close

## 8. Existing Tests Update

- [x] 8.1 Verify existing `WsRpcChannelTest` and `WsRpcStreamTest` still pass
- [x] 8.2 Verify no regression in request/response, notifications, streams