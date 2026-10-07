## 1. Envelope and protocol constants

- [ ] 1.1 Add `kind` and `event` fields to `gateway/src/main/java/gateway/WsMessage.java`; remove the `error` field and update `create`, `response`, `error`/reply factories to emit `kind`.
- [ ] 1.2 Define kind constants in `gateway/src/main/java/gateway/rpc/WsProtocol.java`: `request`, `response`, `response-error`, `notification`, `stream-start`, `stream-done`, `stream-abort`, `stream-reply-start`, `stream-reply-done`, `listen`, `listening`, `event`, `unlisten`, `listen-ended`, `listen-error`.
- [ ] 1.3 Delete `WsProtocol.rejectStreamControlType` and remove every call site in `RpcProtocol`, `StreamProtocol`, and the event-stream protocol.
- [ ] 1.4 Update `gateway/src/main/java/gateway/rpc/Frames.java` builders to construct kind-based envelopes (success/error answers, stream and reply-stream envelopes).

## 2. Channel dispatch

- [ ] 2.1 Replace the routing body of `WsRpcChannel.dispatchText` with an explicit `switch (kind)` mapping each kind to exactly one protocol table.
- [ ] 2.2 Drop a frame with absent or unknown `kind` with a log and no reply; keep the unparsable-frame drop.
- [ ] 2.3 Validate the subject field per kind (`type` for RPC and byte streams, `event` for event streams) and drop mismatches with a log.

## 3. RPC protocol

- [ ] 3.1 In `RpcProtocol`, emit `kind="request"` for requests and `kind="notification"` for notifications.
- [ ] 3.2 Settle pending calls on `kind="response"` and `kind="response-error"` only.
- [ ] 3.3 Answer an unhandled request `type` with `response-error`; never answer a `notification`, even for an unknown type.
- [ ] 3.4 Update handler-exception handling to emit `response-error` with no `error` boolean.
- [ ] 3.5 Update `emitReplyStream` to use `stream-reply-start` and `stream-reply-done` with `responseOf` equal to the request `id`.

## 4. Byte streams

- [ ] 4.1 `StreamProtocol.sendStream` emits `kind="stream-start"` with the handler in `type` and no `streamType` payload field; `stream-done` echoes `type`.
- [ ] 4.2 `handleStreamStart`/`handleStreamDone` read the handler from the frame `type` and handle `stream-reply-start`/`stream-reply-done` for reply streams.
- [ ] 4.3 Route reply streams from `stream-reply-start`/`stream-reply-done` into the pending-call table; drop a reply start with no pending request.
- [ ] 4.4 Change `abortStream` to accept the handler type, emit a single `stream-abort` carrying `type`, and refuse to abort reply streams.
- [ ] 4.5 Replace all stream failure frames with `response-error`; keep chunk framing, integrity, cap, and timeout behavior unchanged.

## 5. Event streams

- [ ] 5.1 Rework `gateway/src/main/java/gateway/rpc/SubscriptionProtocol.java` to emit and handle `listen`, `listening`, `event`, `unlisten`, `listen-ended`, `listen-error`, keyed by the `event` field.
- [ ] 5.2 Rename the client API to `listen(event, data, handler)`; complete the returned future on `listening` and fail it on `listen-error`.
- [ ] 5.3 Rename `unsubscribe` to `unlisten`; expect and handle the publisher's `listen-ended` confirmation.
- [ ] 5.4 Update `PushHandle`/`DefaultPushHandle` so `push` emits an `event` frame, `complete` emits `listen-ended`, and `fail` emits `listen-error`.
- [ ] 5.5 Rename `registerSubscriptionHandler` to `registerEventListener` and remove reserved-name rejection.

## 6. Server integration

- [ ] 6.1 Update `server/src/main/java/server/service/WsHandler.java` event forwarding to emit `kind="notification"` frames.
- [ ] 6.2 Update `server/src/main/java/server/service/BackendRpc.java` and subscription handlers to use the `listen`/`event` APIs and v2 frames.
- [ ] 6.3 Update `server/src/main/java/server/service/GatewayService.java` handler registration to the renamed event-listener registration.

## 7. Tests

- [ ] 7.1 Update `gateway/src/test/java/gateway/WsRpcChannelTest.java` to assert v2 frames (`kind`, no `error` boolean).
- [ ] 7.2 Update `gateway/src/test/java/gateway/WsRpcStreamTest.java` for `stream-start`/`stream-done` `type` echo and `stream-reply-start`/`stream-reply-done`.
- [ ] 7.3 Update `gateway/src/test/java/gateway/WsRpcSubscriptionTest.java` for `listen`/`listening`/`event`/`unlisten`/`listen-ended`/`listen-error`.
- [ ] 7.4 Add frame-protocol tests: unknown/absent `kind` drop, notification never answered, and subject-field validation.
- [ ] 7.5 Run `./gradlew :gateway:test :server:test` and fix failures.

## 8. Documentation

- [x] 8.1 Rewrite `WS-RPC.md` to the v2 grammar.
- [x] 8.2 Write `WS-RPC-v2.md` as the change supplement.
