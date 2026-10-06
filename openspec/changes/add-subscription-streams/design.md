## Context

The `WsRpcChannel` in the `gateway` module currently supports:
- Request/response RPC with correlation IDs
- Fire-and-forget notifications
- Metadata-first byte streams with SHA-256 integrity (Section 7 of WS-RPC)
- Reply-with-stream for large responses
- Stream abort notifications

The server module uses SSE (`/getUsage`) for streaming server metrics, which has limitations:
- Separate HTTP connection required
- No per-subscriber parameter filtering (e.g., by `serverId`)
- No lazy evaluation (runs regardless of subscribers)
- No explicit subscription lifecycle

WS-RPC Section 13 defines Subscription Streams as a native WebSocket feature that addresses these limitations.

## Goals / Non-Goals

**Goals:**
- Implement WS-RPC Section 13 subscription streams in `WsRpcChannel`
- Client API: `subscribe(eventType, data, handler)` and `unsubscribe(requestId)`
- Server API: `registerSubscriptionHandler(eventType, paramsClass, onSubscribe)` returning `PushHandle`
- Per-subscription lazy initialization and cleanup
- Wire protocol compliance with `subscribe`/`unsubscribe` reserved types
- Multiple independent subscriptions per `eventType` with different `data`
- Connection-close cleanup (no resume/replay)

**Non-Goals:**
- Event replay on reconnection
- Flow control / backpressure (drop-on-overflow per WS-RPC)
- Server-side subscription persistence
- Rate limiting or quotas
- Changes to transport layer or `WsSession` interface

## Decisions

### 1. Reserved Types: `subscribe` and `unsubscribe`

**Decision**: Add `SUBSCRIBE_TYPE = "subscribe"` and `UNSUBSCRIBE_TYPE = "unsubscribe"` as reserved control types alongside `stream-start`, `stream-done`, `stream-abort`.

**Rationale**: WS-RPC Section 13.1 explicitly reserves these types. They must be rejected if used for handler registration.

**Alternatives**: Could use a prefix like `sub.` but protocol spec uses exact names.

### 2. Subscription ID = Original Request ID

**Decision**: The subscription ID for all subsequent frames is the `subscribe` request's `id` (per WS-RPC Section 13.3).

**Rationale**: Protocol spec is explicit. This enables correlation without extra fields.

### 3. PushHandle as Subscription Control Object

**Decision**: `onSubscribe` returns `CompletableFuture<PushHandle>` where `PushHandle` provides:
- `push(Object event)` - sends event frame with `type=eventType`, `responseOf=subscriptionId`
- `complete()` - sends error frame with `error=true` (or just stops pushing; protocol uses error frame for termination)
- `fail(String reason)` - sends error frame with `error=true`
- `isClosed()` - true if client unsubscribed or connection lost
- `onClose(Runnable callback)` - cleanup registration

**Rationale**: Encapsulates per-subscription state and provides clean API for server code. The `CompletableFuture` allows async initialization (e.g., waiting for resources).

**Alternatives**: Could return `PushHandle` directly (sync) but async is more flexible.

### 4. Event Frames as Answer Frames

**Decision**: Events are standard answer frames: `type=eventType`, `responseOf=subscriptionId`, `error=false`, fresh `id` per event (per WS-RPC Section 13.4).

**Rationale**: Protocol spec defines this explicitly. Reuses existing answer routing logic.

### 5. Client Handler Registration

**Decision**: `subscribe(eventType, data, Consumer<JsonNode> handler)` registers a per-subscription handler. The handler is invoked for each event frame with `responseOf=subscriptionId`.

**Rationale**: Simple callback model. The channel routes by `responseOf` to the correct handler.

### 6. Server Handler Registration

**Decision**: `registerSubscriptionHandler(eventType, paramsClass, Function<Params, CompletableFuture<PushHandle>> onSubscribe)`

**Rationale**: 
- `paramsClass` for typed deserialization of `data` payload
- `onSubscribe` called once per subscription (not per event)
- Returns `PushHandle` for that specific subscriber
- Multiple subscriptions to same `eventType` = multiple `onSubscribe` calls

### 7. Subscription Tracking in Channel

**Decision**: Track subscriptions in `Map<UUID, SubscriptionSlot>` keyed by subscription ID (request ID). Each slot holds:
- `eventType`
- `params` (deserialized)
- `handler` (client) or `pushHandle` (server)
- `closed` flag

**Rationale**: Enables O(1) lookup for event routing, unsubscribe, and close cleanup.

### 8. Error Frame for Server-Initiated Termination

**Decision**: Server calls `PushHandle.fail(reason)` → sends frame with `type=eventType`, `responseOf=subscriptionId`, `error=true`, payload=reason (per WS-RPC Section 13.6).

**Rationale**: Protocol spec. Client treats this as subscription end.

### 9. Unsubscribe is Fire-and-Forget

**Decision**: Client calls `unsubscribe(requestId)` → sends `unsubscribe` frame with `responseOf=requestId`. No reply expected (per WS-RPC Section 13.5).

**Rationale**: Protocol spec. Publisher MUST NOT send a reply frame.

### 10. Close Cleanup

**Decision**: On `onClose`, iterate all subscriptions, call `onClose` callbacks, clear map.

**Rationale**: Protocol Section 13.7 - connection close terminates all subscriptions.

## Risks / Trade-offs

| Risk | Mitigation |
|------|------------|
| Memory leak if subscriptions not cleaned up on close | `onClose` clears all slots and calls `onClose` callbacks |
| Duplicate subscription IDs | UUID v4 collision probability negligible |
| Event handler exceptions crash channel | Wrap handler in try-catch, log, don't propagate |
| Unbounded event queue if client slow | Drop-on-overflow per WS-RPC 13.4; no buffering in channel |
| Server `onSubscribe` never completes | Client subscription future times out (use existing operation timeout) |
| Race: event arrives before handler registered | Channel routes by `responseOf`; if no slot, drop with log (like unknown stream) |

## Migration Plan

1. Add reserved types and validation in `WsRpcChannel`
2. Add client `subscribe`/`unsubscribe` methods
3. Add server `registerSubscriptionHandler` and `PushHandle` interface
4. Implement wire frame handling in `onTextMessage`
5. Add `SubscriptionSlot` tracking
6. Write tests covering all scenarios in WS-RPC Section 13
7. Replace SSE `/getUsage` with subscription-based usage (follow-up)

## Open Questions

- Should `PushHandle.push()` accept typed objects or only `JsonNode`? (Proposal: `Object`, let mapper serialize)
- Should there be a default operation timeout for `subscribe` future? (Proposal: reuse `DEFAULT_TIMEOUT` = 2 min)
- Should `registerSubscriptionHandler` support a "last subscriber cleanup" callback? (Proposal: yes, via `PushHandle.onClose`)