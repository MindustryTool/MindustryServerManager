## Context

The `:gateway` module is a shared WS-RPC implementation used by two adapters:

- `JdkWsClient` (client) — the plugin dials the Server Manager. One `WsRpcChannel` per logical client; a new `Transport` (the `WsSession`) per connect.
- The Javalin server side (`GatewayService`) — the Server Manager accepts plugin connections.

`WsRpcChannel` tracks the live session in a volatile `current` field. Outbound initiation (`sendRequest`/`sendNotification`/`sendStream`/`subscribe`) reads `current` through `awaitOpen`; inbound replies are addressed by `responseOf` and driven by `RpcProtocol`/`StreamProtocol`/`SubscriptionProtocol`, which currently reach the wire through a sessionless `FrameSink` that reads `current`.

The startup defect is an ordering problem between the transport and the channel, amplified by the fact that replies re-read `current` on a worker thread instead of remembering the requesting session.

## Goals / Non-Goals

**Goals:**
- No inbound frame is dispatched to a handler before its session is adopted as `current`.
- Every reply, stream ack/error, subscription ack, and pushed event is sent on the session that delivered the originating frame.
- The genuine case — a handler finishing after its own delivering session closed — still drops with the existing INFO log.
- No behavioral change to `responseOf` correlation, `SESSION_WAIT`, 4234 replacement, or fail-all-on-close.

**Non-Goals:**
- Buffering inbound frames in the channel.
- A channel-level detector/guard for "no adopted session".
- Changing the wire protocol or adding DB/API surface.
- The TS backend parity log (separate repository/change).

## Decisions

### 1. Adopt the session before granting any read (Layer 1)

- `Transport.attach(socket)` binds the socket, starts the sender loop and ping task, but does **not** call `socket.request(n)`.
- `Transport.beginRead()` calls `socket.request(1)` exactly once (idempotent guard). It is the only place demand for inbound frames is created.
- `JdkWsClient.onDialSuccess` sequences: `attach` → mark OPEN → `rpcChannel.onOpen(transport)` → `transport.beginRead()`.
- `InnerListener.onOpen` becomes a no-op **and must keep overriding** `onOpen`. The JDK `WebSocket.Listener` default implementation of `onOpen` calls `webSocket.request(1)`; deleting the override would silently re-enable reads before adoption and reintroduce the race.

*Rationale:* the JDK demand-driven API guarantees no frame is delivered until `request(n)`; making adoption happen strictly before the first `request` eliminates the pre-adoption window for every adapter that follows the same contract.

*Alternative considered:* buffer frames in the channel until `onOpen` (`WsRpcChannel` holds a pre-open queue). Rejected: adds bounded-buffer policy, flush/stale-session rules, and hides the ordering contract instead of fixing it.

### 2. Bind a frame to its delivering session via an inbound context (Layer 2)

- Introduce a package-private generic `FrameContext<T>(T body, WsSession origin)` — the per-frame carrier of "what arrived" and "who delivered it". Text ingress uses `T = WsMessage<JsonNode>`; binary ingress does not use it today (see below), but the generic body avoids a second type and future dispatch-signature churn. It is created inside the channel after parsing, so the transport-facing API stays `onTextMessage(WsSession from, String json)`.
- `dispatchText` builds the context once and routes on `context.body().getKind()`. Protocol entry points take the context: `rpc.dispatchRequest(ctx)`, `rpc.dispatchNotification(ctx)`, `stream.handleControl(ctx)`, `subscriptions.handleControl(ctx)`. Correlation-only frames (`response`/`response-error`, `listening`/`event`/`listen-ended`/`listen-error`) are keyed by id and ignore the origin but travel through the same context for uniformity.
- `invokeHandler(ctx, entry)` sends via `sendTo(ctx.origin(), response)`; `emitReplyStream` uses `ctx.origin()`.
- `FrameSink` becomes `send(WsSession target, WsMessage<?> message)`. Slots that can settle or push later — stream slots, server/client subscription slots, `DefaultPushHandle` — store the extracted `WsSession origin` (not the whole context, so the frame payload is not retained) and pass it to the sink.
- **Binary ingress needs no context.** `handleBinaryMessage(ByteBuffer)` resolves the slot by `streamId`, and the slot already holds its origin.
- Outbound *initiation* still uses `current` via `awaitOpen`; only answers and pushes are bound.

*Rationale:* `current` is mutable and may change between reception and reply (reconnect/4234). The socket that delivered a request is the only correct destination for its answer; any other session cannot correlate `responseOf`. The context is preferred over a bare `WsSession` parameter as the seam for future per-frame metadata (trace/sequence) and a possible handler-context API, at the cost of one internal type.

*Alternatives considered:*
- *Bare `WsSession origin` parameter.* Fewer concepts, but adding a second per-frame field later re-touches every dispatch signature.
- *Keep `current` and only fix read ordering (Layer 1).* Rejected: leaves the rarer mid-handler replacement misrouting, where a reply is sent to a replacement peer that never sent the request.

### 2a. No speculative context fields

The context carries only `body` + `origin`, and its body is generic so a different frame shape (e.g. binary) can reuse the type without a new class. Connection generation/epoch, principal/managerId, and trace ids are deliberately omitted: transport identity is already per-connect (a fresh `Transport` each time), the channel is scoped to one logical client, and observability fields can be added when a consumer exists.

### 3. Drop semantics unchanged for a closed origin

- `sendTo(origin, message)` drops with the existing `Dropping RPC response, no open session for type=` INFO log when `origin == null || !origin.isOpen()`.
- After the fix this fires only when the origin session genuinely closed; that is correct and stays at INFO.

### 4. `current` remains for gating and initiation only

- `SessionGate.current()` / `awaitOpen()` keep their meaning for outbound sends.
- No new `adopted` flag is introduced; adoption is simply "the first `request(n)` happens after `onOpen`", which is enforced by the transport ordering.

## Risks / Trade-offs

- **[JDK default `onOpen` re-enables reads]** → keep an explicit no-op `onOpen` override; add a regression test that fails if frames arrive before `onOpen` is signalled.
- **[`FrameSink` signature churn touches every protocol]** → mechanical but wide; compile errors surface every missed call site.
- **[Inbound entry-point signature change breaks callers]** → only the text entry point changes; two call sites (`Transport.InnerListener.onText`, `GatewayService.onMessage`) pass their known session. Binary ingress is untouched.
- **[New internal type spread]** → `FrameContext` stays package-private in `gateway.rpc`; only text dispatch methods see it.
- **[An outbound initiated stream's own acks]** → store the session on the outgoing stream slot too, so timer-driven `stream-abort` and error frames are not sent to a replacement peer.
- **[Tests relying on `onTextMessage(String)`]** → update to the session-parameterised form; prefer updating call sites so no stale path remains.
