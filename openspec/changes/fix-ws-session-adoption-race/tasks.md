## 1. Layer 1 — adopt before granting reads (`:gateway` client)

- [x] 1.1 `Transport.attach` binds the socket and starts sender/ping; remove `socket.request(1)`
- [x] 1.2 Add idempotent `Transport.beginRead()` that alone calls `socket.request(1)`
- [x] 1.3 Make `InnerListener.onOpen` a no-op override that no longer requests (keep the override so the JDK default cannot request)
- [x] 1.4 In `JdkWsClient.onDialSuccess`, call `rpcChannel.onOpen(transport)` before `transport.beginRead()`
- [x] 1.5 Confirm no other `request(n)` call exists on the inbound path

## 2. Layer 2 — bind inbound frames to the delivering session (`:gateway` rpc)

- [x] 2.1 Add package-private generic `FrameContext<T>(T body, WsSession origin)` in `gateway/rpc` (text uses `T = WsMessage<JsonNode>`)
- [x] 2.2 Change `WsRpcChannel.onTextMessage` to take the delivering `WsSession`; build `FrameContext<WsMessage<JsonNode>>` after parsing and route on `ctx.body().getKind()`
- [x] 2.3 Thread the context into `RpcProtocol.dispatchRequest`/`dispatchNotification`, `StreamProtocol.handleControl`, `SubscriptionProtocol.handleControl`
- [x] 2.4 `RpcProtocol.invokeHandler(ctx, entry)` sends replies and `emitReplyStream` on `ctx.origin()`, not `sessions.current()`
- [x] 2.5 Change `FrameSink` to `send(WsSession target, WsMessage<?>)`; expose `sendRaw(target, message)` as the sink
- [x] 2.6 Store the extracted `WsSession origin` on stream slots and outgoing sender streams; send stream acks/errors/aborts on it (do not retain the context)
- [x] 2.7 Store the extracted `WsSession origin` on server/client subscription slots and `DefaultPushHandle`; push `event`/`listen-ended`/`listen-error` on it
- [x] 2.8 Leave binary ingress (`handleBinaryMessage(ByteBuffer)`) context-free; it resolves origin from the stream slot
- [x] 2.9 Keep `current` for outbound initiation (`sendRequest`/`sendNotification`/`sendStream`/`subscribe`/`unlisten`) only; peer-initiated aborts use the sender stream's origin

## 3. Server edge (`:server`)

- [x] 3.1 `GatewayService.onMessage` passes its `JavalinSession` into `onTextMessage` (binary ingress is unchanged per 2.8)

## 4. Tests

- [x] 4.1 Ordering regression: a request delivered with an open origin while `current` is unset still gets its reply
- [x] 4.2 Replacement regression: a request received on generation A whose handler finishes after A is replaced by B is not answered on B
- [x] 4.3 Closed-origin regression: a handler finishing after its own session closed drops with no frame
- [x] 4.4 Stream and subscription acks/pushes go to the delivering session, not `current` (unit)
- [x] 4.5 No-frame-before-adoption: reads are granted only once after adoption and `InnerListener.onOpen` grants none

## 5. Verify

- [x] 5.1 Run `:gateway` plus `:server` test suites green
- [ ] 5.2 Reproduce the startup log: `is-hosting`/`get-state` replies flow at connect with no `no open session` lines

## 6. Follow-up (separate repo)

- [ ] 6.1 `MindustryToolWeb`: add a matching TS drop log on non-OPEN sends (separate change)
