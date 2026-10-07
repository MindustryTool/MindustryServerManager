# gateway-module

## Purpose

TBD - created by syncing change extract-gateway-subproject. Update Purpose after implementation.

## Requirements

### Requirement: Standalone Gateway Module Setup
The system SHALL provide a dedicated Gradle subproject `:gateway` that compiles with Java 17 and packages `gateway.WsMessage`, `gateway.session.WsSession`, and `gateway.rpc.WsRpcChannel` without depending on Mindustry or Arc libraries.

#### Scenario: Subproject compilation
- **WHEN** `./gradlew :gateway:build` is executed
- **THEN** `:gateway` compiles successfully with only standard Java and Jackson dependencies

### Requirement: Transport Abstraction WsSession
The `:gateway` module SHALL define a transport interface `WsSession` providing methods to send text messages, send binary buffers, close the session with status code and reason, and query whether the connection is currently open.

#### Scenario: Adapter invokes sendText
- **WHEN** an RPC channel sends a formatted JSON payload through a `WsSession`
- **THEN** `WsSession.sendText()` delegates to the underlying transport implementation

### Requirement: Request-Response RPC Channel Multiplexing
The `:gateway` module SHALL provide `WsRpcChannel` capable of matching asynchronous request `id` values with subsequent `response`/`response-error` `responseOf` values using `CompletableFuture`, dispatching incoming frames on `kind` to registered typed message handlers, replying with a `response-error` frame to unhandled request types and never answering a `notification`, holding a volatile current session with gate-wait (no stored future) so outbound requests wait until an open session appears (up to a session-wait limit), overwriting the current session on `onOpen` (closing a different live session with code `4234`), ignoring stale closes for non-current sessions, and completing pending and waiting requests exceptionally when timeouts expire or the current connection closes.

#### Scenario: Asynchronous request-response correlation
- **WHEN** `WsRpcChannel.sendRequest()` is invoked with a message type and payload
- **THEN** it generates a unique message `id`, stores a pending future, transmits the `WsMessage` with `kind="request"`, and completes the future when a matching `responseOf` `response` frame arrives

#### Scenario: Request timeout handling
- **WHEN** a remote peer does not respond within the configured timeout duration
- **THEN** the pending future is completed exceptionally with a `TimeoutException` and removed from memory

#### Scenario: Incoming message handler execution
- **WHEN** a `request` frame arrives matching a registered message handler type
- **THEN** `WsRpcChannel` deserializes the payload, executes the handler, and transmits a `response` frame containing the matching `responseOf` ID

#### Scenario: Unhandled request type fails fast
- **WHEN** a `request` frame arrives whose type has no registered handler
- **THEN** `WsRpcChannel` transmits a `response-error` frame with `responseOf` set to the incoming `id` and a payload naming the unknown type, instead of dropping the frame silently

#### Scenario: Notification is never answered
- **WHEN** a `notification` frame arrives, even with an unregistered type
- **THEN** `WsRpcChannel` processes it and transmits no frame back

#### Scenario: Request suspends until session is open
- **WHEN** `sendRequest()` is invoked while no `WsSession` is open
- **THEN** the channel parks the request on the gate without failing and transmits it once a session becomes open, instead of completing exceptionally with `IllegalStateException`

#### Scenario: Session-wait expiry
- **WHEN** no open session appears within the session-wait limit (300 s)
- **THEN** the pending future is completed exceptionally with a `TimeoutException` and removed from memory

#### Scenario: Close fails waiters and live requests
- **WHEN** the current session closes (non-stale `onClose` or `shutdown`)
- **THEN** each queued gate waiter and each live pending future is completed exceptionally with the close cause; sends started after the close wait fresh for the next session

#### Scenario: Overwrite on open closes old with 4234
- **WHEN** `onOpen` receives a new open session while a different session is current
- **THEN** the old session is closed with code `4234`, the new session becomes current, and gate waiters are woken; null or non-open sessions still throw

### Requirement: WsMessage nesting guard
The `:gateway` module SHALL reject a `WsMessage` instance used as another `WsMessage` payload at construction time by throwing `IllegalArgumentException`.

#### Scenario: withPayload rejects nested message
- **WHEN** `withPayload` is called with a `WsMessage` instance as the payload
- **THEN** the call throws `IllegalArgumentException` and no message is transmitted

#### Scenario: response and error reject nested message
- **WHEN** `response` or `error` is called with a `WsMessage` instance as the payload
- **THEN** the call throws `IllegalArgumentException` and no response frame is transmitted

### Requirement: Handler exception error frame
`WsRpcChannel` SHALL convert a handler exception into a `response-error` frame with `responseOf` set to the incoming request `id`. The `error` boolean SHALL NOT be used.

#### Scenario: Handler throw yields response-error frame
- **WHEN** a registered handler throws for an incoming request
- **THEN** the channel transmits a `response-error` frame with `responseOf` equal to the request `id` and the exception detail in the payload

#### Scenario: Failure frame never triggers a reply
- **WHEN** a `response` or `response-error` frame with `responseOf` set arrives
- **THEN** the channel completes or fails the matching pending future and transmits no further frame

### Requirement: Notification session patience
The `:gateway` module SHALL suspend `WsRpcChannel.sendNotification()` until an open session appears (up to the same session-wait limit) instead of dropping immediately, and SHALL drop with a log on session-wait expiry or connection close.

#### Scenario: Notification waits for session
- **WHEN** `sendNotification()` is invoked while no `WsSession` is open
- **THEN** the channel holds the notification and transmits it once a session becomes open within the session-wait limit

#### Scenario: Notification expiry or close drops
- **WHEN** the session-wait limit expires or `onClose`/`shutdown` happens before a session opens
- **THEN** the notification is dropped with a log and no exception propagates to the caller

### Requirement: Stream transport on WsRpcChannel
The `:gateway` module SHALL extend `WsRpcChannel` with `sendStream` overloads (`ByteBuffer`, `byte[]`, `InputStream`) returning a typed-ack future, `registerStreamHandler`/`unregisterStreamHandler` for `(metadata, assembledBytes)` handlers, `onBinaryMessage` ingest for binary chunk frames, and `abortStream` cancel, reusing session-wait, op timeout, and `onClose` cleanup semantics from request-response RPC.

#### Scenario: Stream API suspends until session open
- **WHEN** `sendStream()` is invoked while no `WsSession` is open
- **THEN** the channel holds `start`, chunks, and `done` and transmits them in order once a session becomes open within the session-wait limit

#### Scenario: Stream timeout and close mirror request behavior
- **WHEN** a stream exceeds the op timeout or the connection closes mid-stream
- **THEN** the pending stream future is completed exceptionally and receiver buffers for that `streamId` are discarded

### Requirement: WsSession call-order contract for streams
`WsSession` implementations SHALL transmit `sendText`/`sendBinary` calls in call order per session so a `start` frame is never reordered after its chunks on the wire.

#### Scenario: Adapter preserves start-chunks-done order
- **WHEN** the channel emits `start` text, N binary frames, then `done` text through one session
- **THEN** the transport delivers them to the peer in that same order

### Requirement: Reply-with-stream on WsRpcChannel
The `:gateway` module SHALL let an RPC handler answer its incoming request with a stream using dedicated kinds: emitted `stream-reply-start`/`stream-reply-done` envelopes carry `responseOf` equal to the request `id` and `type` naming the stream handler, chunks are keyed by a fresh `streamId` as usual, and the requester's pending future resolves with the assembled bytes instead of a separate stream ack.

#### Scenario: Reply stream resolves the request
- **WHEN** a handler answers a request with a reply stream and all chunks verify
- **THEN** the requester's `sendRequest` future completes with the assembled bytes

#### Scenario: Reply stream failure fails the request
- **WHEN** a reply stream fails integrity, cap, or handler checks
- **THEN** the requester's future fails with an error and no separate ack is sent

### Requirement: Wire abort handling with auto-notify
`WsRpcChannel` SHALL handle inbound `stream-abort` notifications by discarding the slot and buffers and settling the waiter with the reason, never replying. `abortStream` SHALL discard local state and emit a `stream-abort` notification, echoing the stream handler in `type`, when a session is open. Reply streams SHALL NOT be abortable. No application name SHALL be reserved.

#### Scenario: Inbound abort settles waiter silently
- **WHEN** a `stream-abort` notification arrives for a live stream
- **THEN** the pending future fails with the reason and no frame is transmitted back

#### Scenario: Abort notifies the peer with type
- **WHEN** `abortStream` is called for a live stream while a session is open
- **THEN** a `stream-abort` notification carrying the stream handler in `type` is emitted and local state is discarded

#### Scenario: Reply stream is not abortable
- **WHEN** `abortStream` is called for a reply stream
- **THEN** no `stream-abort` notification is emitted for it

### Requirement: Sliding stream timeout with ceiling
`WsRpcChannel` SHALL refresh the receiver slot timeout on every stream frame, bounded by an absolute 300 s ceiling from slot reserve.

#### Scenario: Refresh on chunk
- **WHEN** a chunk arrives for a live slot
- **THEN** the slot timeout is re-armed unless the 300 s ceiling is reached

### Requirement: Chunk bounds check at ingest
`WsRpcChannel.onBinaryMessage` SHALL reject a chunk whose index is outside `0..totalChunks-1` by discarding the slot and failing the sender without waiting for `done`.

#### Scenario: Out-of-range index fails fast
- **WHEN** a chunk arrives with an index at or above `totalChunks`
- **THEN** the slot is discarded and the sender future fails

### Requirement: Channel session wait access
`WsRpcChannel` SHALL expose `awaitSession(Duration timeout)` returning a future that completes with the current open session, or completes exceptionally with a `TimeoutException` when none appears within the timeout. This SHALL be the only session-wait entry point; clients SHALL NOT wrap it.

#### Scenario: Session available now
- **WHEN** `awaitSession` is called while a session is open
- **THEN** the future completes immediately with that session

#### Scenario: Session opens within timeout
- **WHEN** `awaitSession` is called with no open session and one opens before the timeout
- **THEN** the future completes with the opened session

#### Scenario: Timeout with no session
- **WHEN** no session opens within the timeout
- **THEN** the future completes exceptionally with a `TimeoutException`

#### Scenario: Close fails the waiter
- **WHEN** the current session closes while a waiter is parked
- **THEN** the waiter completes exceptionally with the close cause
