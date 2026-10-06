## Why

The current system uses an SSE-based `/getUsage` endpoint for server metrics streaming, which requires a separate HTTP connection and lacks per-subscriber parameter support (e.g., `serverId`). WS-RPC Section 13 defines a native subscription stream protocol over the existing WebSocket connection that supports typed event streams with per-subscription parameters, lazy evaluation, and explicit lifecycle management.

## What Changes

- Add `subscribe` and `unsubscribe` message types to `WsRpcChannel` (reserved types per WS-RPC Section 13)
- Client API: `subscribe(eventType, data, handler)` → `CompletableFuture<Void>` (acknowledged on first event)
- Client API: `unsubscribe(requestId)` to cancel a subscription
- Server API: `registerSubscriptionHandler(eventType, paramsClass, onSubscribe)` where `onSubscribe` returns a `PushHandle` for pushing events to that specific subscriber
- Per-subscription handler receives typed parameters and returns a `PushHandle` with `push(event)`, `complete()`, `fail(reason)`, `isClosed()`, `onClose(callback)`
- Multiple concurrent subscriptions to the same `eventType` with different `data` parameters are independent
- Server-initiated termination via error frame on the subscription's `eventType` with `responseOf` = subscription ID
- On connection close, all subscriptions are terminated and cleaned up
- No protocol-level resumption or replay on reconnection

## Capabilities

### New Capabilities

- `subscription-streams`: Client-side subscription management (subscribe, unsubscribe, event handler registration)
- `subscription-push`: Server-side push handle for emitting events per subscriber with lazy initialization and cleanup
- `subscription-wire-protocol`: Wire protocol compliance for `subscribe`/`unsubscribe` frames per WS-RPC Section 13

### Modified Capabilities

- `ws-rpc-stream-protocol`: Add reserved types `subscribe` and `unsubscribe` to control frame types; extend stream semantics to include subscription streams (distinct from Section 7 streams - no binary chunks, no predefined length, identified by subscribe request ID)

## Impact

- `gateway/src/main/java/gateway/rpc/WsRpcChannel.java` - Core channel extension with subscription APIs
- `gateway/src/main/java/gateway/WsMessage.java` - No changes needed (already supports arbitrary types)
- `gateway/src/main/java/gateway/stream/` - No changes (subscription streams use text frames only, no binary chunks)
- New test file: `gateway/src/test/java/gateway/WsRpcSubscriptionTest.java`
- Potential integration with server's usage endpoint replacement