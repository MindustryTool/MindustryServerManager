## Why

The v1 wire protocol overloads two envelope fields. `type` means both a frame kind (`subscribe`, `stream-start`) and an application name (handler, stream type, event topic), forcing a reserved-word list that permanently poisons the application namespace. `responseOf` means an RPC answer, a reply-with-stream envelope, and a subscription event, distinguished only by probing two maps in a fixed order. This makes routing implicit and brittle, and forces every new control frame to claim another reserved name.

## What Changes

- **BREAKING** Add a required `kind` field to every text frame as the explicit frame discriminator. A frame with an absent or unknown `kind` is dropped with a log and no reply.
- **BREAKING** Remove the reserved application types (`subscribe`, `unsubscribe`, `stream-start`, `stream-done`, `stream-abort`). Any handler, stream type, or event name becomes legal.
- **BREAKING** Remove the `error` boolean. Failure is encoded by kind: `response-error` for RPC answers, `listen-error` for event streams.
- **BREAKING** Route on `kind` alone; each kind maps to exactly one receiver table.
- RPC and byte-stream frames name their subject with `type`; event-stream frames name theirs with `event`.
- **BREAKING** Replace subscription frames with an SSE-like event-stream family: `listen`, `listening`, `event`, `unlisten`, `listen-ended`, `listen-error`. The event name moves from `payload.eventType` to the `event` field.
- A `listen` is acknowledged with a dedicated `listening` frame. An `unlisten` is confirmed with `listen-ended`.
- Notifications become a distinct kind and are never answered, even for an unregistered `type`.
- **BREAKING** Reply-with-stream uses dedicated kinds `stream-reply-start` and `stream-reply-done` instead of a `responseOf`-tagged `stream-start`/`stream-done`. Byte-stream frames echo `type`.
- Deferred/out of scope: version negotiation, error codes, subscription flow control.

## Capabilities

### New Capabilities
- `ws-rpc-frame-protocol`: The v2 text envelope (`kind`, `type`, `event`, `responseOf`), kind-based routing table, kind-encoded failures, and notification semantics.

### Modified Capabilities
- `ws-rpc-stream-protocol`: Stream control envelopes gain `kind` and echo `type`; reply-with-stream uses `stream-reply-start`/`stream-reply-done`; `error` flag removed; reserved types removed.
- `subscription-wire-protocol`: Subscription frames replaced by the event-stream family with the `event` field; `listening` ack; `listen-ended` confirmation; `listen-error` termination.
- `subscription-streams`: Client uses `listen`/`unlisten`, completes its future on `listening`, and waits for `listen-ended` confirmation.
- `subscription-push`: Server pushes `event` frames keyed by the listen ID and terminates with `listen-ended`/`listen-error`.
- `chunked-file-streaming`: Download reply-with-stream envelopes become `stream-reply-start`/`stream-reply-done`.
- `backend-rpc-gateway`: The `WsMessage` envelope carries `kind`; backend command dispatch and replies use v2 frames.
- `gateway-module`: `WsRpcChannel` dispatches on `kind`, never answers notifications, and answers failures with `response-error`/`listen-error`.

## Impact

- Wire docs: `WS-RPC.md` rewritten to v2; `WS-RPC-v2.md` is the change supplement.
- `gateway/src/main/java/gateway/WsMessage.java`: add `kind`, `event`; remove `error`.
- `gateway/src/main/java/gateway/rpc/`: `WsProtocol`, `WsRpcChannel`, `RpcProtocol`, `StreamProtocol`, `SubscriptionProtocol`, and frame/slot helpers.
- `server/src/main/java/server/service/`: `WsHandler`, `BackendRpc`, subscription handlers.
- Reserved-type rejection (`WsProtocol.rejectStreamControlType`) removed with its call sites.
- Existing tests for channels, streams, and subscriptions updated to v2 frames.
- No version negotiation is required because v1 is not deployed anywhere.
