## Why

On gateway startup the log shows the socket connecting and the channel answering as if no session exists:

```
19:24:43:493  Dropping RPC response, no open session for type=is-hosting
19:24:43:493  Connected to server manager
19:24:43:750  Dropping RPC response, no open session for type=get-state
```

The socket is open the whole time. Two distinct races let inbound frames be dispatched before (or detached from) the session that delivered them:

1. **Pre-adoption dispatch.** `JdkWsClient.onDialSuccess` calls `Transport.attach`, which grants reads via `socket.request(1)` (`Transport.java:99`), and `Transport.InnerListener.onOpen` also grants reads (`Transport.java:344`) — both before `WsRpcChannel.onOpen` adopts the transport (`JdkWsClient.java:350`). The Server Manager sends `is-hosting`/`get-state` immediately on connect, so those frames are dispatched while `current == null` and every reply is dropped.
2. **Late `current` read.** `RpcProtocol.dispatchRequest` runs the handler on an executor (`RpcProtocol.java:226`); `invokeHandler` reads `sessions.current()` at execution time (`RpcProtocol.java:260`), not the session that delivered the frame. A reconnect in that gap sends the reply on a peer that never issued the request.

A guard or extra log would only detect this. The fix removes the window: adopt before granting reads, and bind each inbound frame to its delivering session.

## What Changes

- **Adopt before reading (Layer 1).** `Transport.attach` binds the socket and starts the sender/ping without requesting frames; a new `Transport.beginRead()` grants the first read; `JdkWsClient.onDialSuccess` calls `rpcChannel.onOpen(transport)` before `beginRead()`. `InnerListener.onOpen` stops requesting reads on its own (the override must remain, because the JDK `WebSocket.Listener` default `onOpen` requests one frame automatically).
- **Bind replies to the delivering session (Layer 2).** A package-private generic `FrameContext<T>(body, origin)` is built at text ingress (`T = WsMessage<JsonNode>`) and threaded through dispatch. Request replies, stream acks/errors/aborts, reply-with-stream, subscription acks, and pushed events are sent on the originating session rather than on the mutable `current`. `FrameSink`, stream slots, subscription slots, and push handles carry that session (slots store the session, not the context, to avoid retaining frame payloads). Binary ingress needs no context because the stream slot already holds its origin.
- **Keep the existing drop semantics.** When the originating session is no longer open at send time, the frame is still dropped with the existing INFO log — correct, because the peer is gone and never saw the request.
- **No channel-level guard or buffering.** With the window removed there is nothing to guard; adding a detector would only mask a future ordering regression instead of preventing it.

## Capabilities

### New Capabilities

<!-- None -->

### Modified Capabilities

- `jdk-websocket-client`: reads are granted only after the session is adopted; a fresh transport is attached without enabling inbound delivery.
- `ws-channel-lifecycle`: inbound dispatch requires an adopted session; replies and pushed frames are bound to the delivering session, not to volatile `current`.

## Impact

- `gateway/src/main/java/gateway/client/Transport.java`: split attach from read enablement; `beginRead()`; `InnerListener.onOpen` no longer requests.
- `gateway/src/main/java/gateway/client/JdkWsClient.java`: adopt before `beginRead()` in `onDialSuccess`.
- `gateway/src/main/java/gateway/rpc/WsRpcChannel.java`: session-parameterised text entry point; builds `FrameContext` and carries it through dispatch; `FrameSink` takes a target.
- `gateway/src/main/java/gateway/rpc/FrameContext.java`: new package-private generic record carrying `(body, origin)`.
- `gateway/src/main/java/gateway/rpc/RpcProtocol.java`, `StreamProtocol.java`, `SubscriptionProtocol.java`: dispatch takes the context; send replies/acks/pushes on the originating session; slots hold the session.
- `gateway/src/main/java/gateway/rpc/FrameSink.java`, `DefaultPushHandle.java`: carry the target session.
- `server/src/main/java/server/service/GatewayService.java`: pass the Javalin session into `onTextMessage` (binary ingress is unchanged).
- Tests: `gateway/src/test/java/gateway/WsRpcChannelTest.java`, `WsRpcClientTest`/`JdkWsClientTest`, and a new ordering regression test.
- **Out of scope: TS parity log.** The sibling `MindustryToolWeb` channel drops silently on non-OPEN sends. Adding a matching log there is a separate change in that repository.
