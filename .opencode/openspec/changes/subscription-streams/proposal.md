# Subscription Streams over WS-RPC

## Summary
Add a pub/sub mechanism to `WsRpcChannel` where clients subscribe to event types with parameters, and servers push independent event streams per subscription until explicit unsubscribe.

## Motivation
Replace the existing SSE-based `/getUsage` endpoint with a WebSocket-native pattern that:
- Uses the existing WS-RPC connection (no separate HTTP route)
- Supports per-subscriber parameters (e.g., `serverId`)
- Runs lazy: server logic only executes when at least one subscriber exists
- Each subscriber gets independent event stream (fresh `getUsage()` call per event)

## Wire Protocol

### Subscribe Request
```json
{
  "id": "sub-req-123",
  "type": "subscribe",
  "payload": {
    "eventType": "usage",
    "data": { "serverId": "srv-123" }
  }
}
```

### Event Frames (one per subscriber, independent)
```json
{
  "id": "evt-1",
  "type": "usage",
  "responseOf": "sub-req-123",
  "payload": { "cpu": 45, "mem": "2GB" }
}
{
  "id": "evt-2",
  "type": "usage",
  "responseOf": "sub-req-123",
  "payload": { "cpu": 47, "mem": "2GB" }
}
```

### Unsubscribe Request
```json
{
  "id": "unsub-1",
  "type": "unsubscribe",
  "responseOf": "sub-req-123",
  "payload": { "reason": "client cancel" }
}
```

### Subscription Error (server-initiated)
```json
{
  "id": "err-1",
  "type": "usage",
  "responseOf": "sub-req-123",
  "error": true,
  "payload": "server not found: srv-123"
}
```

## API Design

### Client (WsRpcChannel)
```java
// Subscribe to event type with parameters, receive events via handler
// Returns future that completes when subscription is acknowledged
CompletableFuture<Void> subscribe(String eventType, Object data, Consumer<JsonNode> handler);

// Unsubscribe by original request ID
void unsubscribe(String requestId);
```

### Server (WsRpcChannel)
```java
// Register subscription handler
// onSubscribe called once per subscription, receives typed params
// Returns CompletableFuture<PushHandle> for async initialization
void registerSubscriptionHandler(String eventType, Class<Params> paramsClass,
    Function<Params, CompletableFuture<PushHandle>> onSubscribe);
```

### PushHandle (per-subscriber control)
```java
interface PushHandle {
    void push(Object event);           // send event to this subscriber
    void complete();                   // send stream-done, end cleanly
    void fail(String reason);          // send error frame, end subscription
    boolean isClosed();                // true if client unsubscribed or connection lost
    void onClose(Runnable callback);   // register cleanup callback on subscription end
}
```

## Behavior

| Aspect | Decision |
|--------|----------|
| Subscription ID | Original request `id` |
| Cancellation | Dedicated `unsubscribe` type with `responseOf` |
| Errors | `PushHandle.fail(reason)` sends error frame |
| Reconnection | No resume; client re-subscribes fresh |
| Multiple subs | Multiple subscriptions to same `eventType` allowed (different `data`) |
| Backpressure | Drop on overflow (no buffering) |
| Lazy evaluation | `onSubscribe` called on first subscriber; `PushHandle` cleaned up on last unsubscribe |

## Server Usage Example
```java
channel.registerSubscriptionHandler("usage", UsageParams.class, params ->
    CompletableFuture.supplyAsync(() -> {
        ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(() -> {
            if (handle.isClosed()) { task.cancel(false); return; }
            handle.push(getUsage(params.serverId()));
        }, 0, 1, TimeUnit.SECONDS);
        
        return new PushHandle() {
            public void push(Object e) { /* queue to client */ }
            public void complete() { task.cancel(false); }
            public void fail(String r) { task.cancel(false); }
            public boolean isClosed() { return handle.isClosed(); }
        };
    }, executor)
);
```

## Integration Points
- Extends `WsRpcChannel` with new methods
- Reuses existing `WsMessage` envelope (adds `subscribe`/`unsubscribe` types)
- No changes to transport layer (uses existing WebSocket session)
- Compatible with existing request/response, notifications, streams

## Out of Scope
- Event replay on reconnection
- Flow control / rate limiting
- Server-side subscription persistence