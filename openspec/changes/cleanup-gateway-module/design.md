## Context

`:gateway` is a standalone Java 17 module (~3,400 LOC production) split across `WsMessage`, `client/*` (client state machine, transport, send ops), `rpc/WsRpcChannel`, and `stream/*`. It has a dense test suite (`WsRpcChannelTest`, `WsRpcStreamTest`, `WsRpcSubscriptionTest`, `JdkWsClientTest`, `SendOpTest`, `FileChunkStreamingTest`) that pins its behavior.

Repeated inline blocks and unreferenced members have accumulated. The goal is a mechanical cleanup that keeps every observable behavior and public signature intact.

## Goals / Non-Goals

**Goals**
- Remove dead internal code.
- Collapse duplicated logic into named helpers.
- Keep public API and all runtime behavior identical.
- Keep the change mechanical so review and bisection are easy.

**Non-Goals**
- No wire-protocol, threading, or lifecycle changes.
- No removal of public methods used by `server`/`plugin` (`getUri`, `getReconnectAttempt`, etc.).
- No split of `WsRpcChannel` into new classes in this change (deferred; noted below).

## Decisions

### D1. Remove only internally-scoped dead members
Delete: `JdkWsClient.backoffDelayMillis` (unused), `Transport.isTerminated`/`isSenderAlive` (unused), the unused 5-arg `Transport` constructor, `FileChunkReceiver.chunkCount` (unused), and the never-read `StreamHandlerEntry.responseType` field. Keep the public `registerStreamHandler(..., Class<Res> responseType, ...)` parameter because callers pass it.
*Alternative considered*: also delete public `JdkWsClient.getUri()` (unused in-repo). Rejected — it is public API and out of scope for a "no behavior/API change" pass.

### D2. Unify pending-request take-and-settle
Add one helper that removes a pending future and its timeout task and completes it exceptionally. Route `failReplyRequest`, the two timeout lambdas, `failPending`, and the inbound-response path through it. `failReplyRequest(slot, detail)` becomes `failPending(slot.replyRequestId, new RuntimeException(detail))`.
*Alternative considered*: leave each site inline. Rejected — five copies is the largest single source of noise.

### D3. One stream-failure settlement helper
Replace the four `if (isReply) failReplyRequest else sendStreamError` blocks in `handleStreamDone` (and the ingest-time equivalents) with `settleStreamFailure(slot, detail)`.

### D4. Shared close-cause unwrap
Move the identical `rootCause` from `JdkWsClient` and `Transport` into one package-visible utility.

### D5. Reuse builders
- SHA-256: keep `FileChunkStreamer.sha256Hex`, delete `FileChunkReceiver.sha256Hex` and call the former.
- SHA-256 exceptions: normalize on the streamer's `NoSuchAlgorithmException` handling.
- Reply/error frames: one `reply(responseOf, type, payload, error)` builder backing `errorFor` and `ackFor`.
- RPC typed conversion: one `deserialize(JsonNode, Class<?>, String)` backing `convertParam` and `convertStreamMeta`.
- `FileTransferHeader.encodeFrame` uses `encode()` for the header prefix instead of rewriting it.
- `WsMessage.withPayload` and `setPayload` collapse to one; `response`/`error` share one private builder.

### D6. Subscription teardown helpers
Add `closeClientSubscription(slot, err)` (cancel timer, mark closed, complete ack, run callbacks) and `closeServerSubscription(slot)`; reuse across `handleSubscriptionError`, `unsubscribe`, `handleUnsubscribe`, and `failAll`.

### D7. Small structural tidy-ups (behavior-preserving)
- `JdkWsClient.onDrop`/`onKick` share a `tryRetire(transport)` guard.
- `Transport.InnerListener` uses one terminated-guard helper instead of repeating the check per callback.
- Normalize `HandlerEntry`/`StreamHandlerEntry` construction style.

### Deferred
Splitting stream and subscription state out of `WsRpcChannel` into collaborators is valuable but is a design change with real churn. Track separately.

## Risks / Trade-offs

- [Mis-extracted helper changes a concurrency-sensitive path] → Keep each extraction mechanical; land in small steps; run the full gateway suite after each group.
- [Removing a public member breaks a downstream module] → Restrict removals to private/package-private; verify with a repo-wide reference search before deleting.
- [Two SHA-256 helpers differ in exception type] → Normalize to `NoSuchAlgorithmException` handling in the streamer; digest output is identical.
- [Large diff obscures a behavioral slip] → Order tasks: dead code first, then helper extraction, then builder reuse, each independently testable.

## Migration Plan

1. Remove dead members; build + test.
2. Extract pending-request, stream-settlement, and close-cause helpers; build + test.
3. Reuse builders (hash, reply frame, conversion); build + test.
4. Tidy `onDrop`/`onKick`, listener guard, constructor style; build + test.
5. Delete/replace now-obsolete private methods; final full build.

Rollback: revert the change; no data or wire migration involved.

## Open Questions

- Should the deferred `WsRpcChannel` split be its own change now or later? (Default: later.)
- Is public `JdkWsClient.getUri()` truly unused by external consumers? (Default: keep it.)
