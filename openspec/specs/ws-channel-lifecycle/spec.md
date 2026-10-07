# ws-channel-lifecycle Specification

## Purpose

Lifecycle and dispatch rules of `RpcChannel`: session adoption and overwrite on reconnect, inbound frame binding, gate-wait behavior, and close semantics.
## Requirements
### Requirement: Overwrite on open with 4234 kick

The system SHALL let `RpcChannel.onOpen(newSession)` replace any live session: when a different open session exists it is closed with code `4234` and reason naming the replacement, then the new session becomes current. Null or non-open sessions SHALL still throw. Late `onClose` for a non-current session SHALL be ignored.

#### Scenario: Second open replaces first

- **WHEN** `onOpen(B)` runs while current session `A` is open and `B != A`
- **THEN** `A.close(4234, reason)` is invoked and current becomes `B`

#### Scenario: Same-session open is a no-op success

- **WHEN** `onOpen` receives the session already current
- **THEN** current is unchanged and no close is emitted

#### Scenario: Null or closed session still throws

- **WHEN** `onOpen` receives null or a session reporting not open
- **THEN** it throws without changing current

#### Scenario: Stale close ignored

- **WHEN** `onClose(A)` runs while current is `B`
- **THEN** current stays `B` and no pending work is failed

### Requirement: Fail-all on close

The system SHALL fail all live RPC futures, live stream futures, and client/server subscription slots when the current session closes (non-stale close or shutdown). New sends issued after the close with no open session SHALL fail fast; they SHALL NOT wait for a later open.

#### Scenario: Close fails live work

- **WHEN** the current session closes
- **THEN** live request/stream futures fail with the close cause and subscription close callbacks run

#### Scenario: Post-close sends fail fast

- **WHEN** a send starts after a close with no session open
- **THEN** it fails immediately with `NoSessionException` (notifications drop with a log) instead of waiting

### Requirement: 4234 kick stops auto-reconnect

The client SHALL treat inbound close code `4234` as a kick: fail the channel with the kick cause, log warn with code, skip the reconnect backoff, and allow a later manual `connect()` to clear the kicked flag and redial. Code match is exact; reason text is not consulted.

#### Scenario: Kick does not reconnect

- **WHEN** the current socket closes with code `4234`
- **THEN** no reconnect is scheduled and a warn log records the kick

#### Scenario: Manual connect revives after kick

- **WHEN** `connect()` is called after a kick
- **THEN** the kicked flag clears and a fresh handshake is attempted

#### Scenario: Stale-socket kick ignored

- **WHEN** code `4234` arrives for a superseded generation socket
- **THEN** it is dropped and the live socket is unaffected

### Requirement: Inbound frames dispatch only after adoption

`RpcChannel` SHALL dispatch inbound frames only after a session has been adopted. Inbound entry points SHALL carry the delivering session, and the channel SHALL NOT dispatch while no session is adopted. Frames delivered by a transport whose session was never adopted SHALL NOT reach any handler.

#### Scenario: No dispatch while unadopted

- **WHEN** a frame is delivered to a transport before `onOpen` adopts its session
- **THEN** no handler runs and no reply is attempted

#### Scenario: Dispatch after adoption carries the deliverer

- **WHEN** a frame is delivered after its transport is adopted
- **THEN** the delivering session is captured at ingress and carried through dispatch

### Requirement: Replies and pushes bind to the delivering session

`RpcChannel` SHALL answer an inbound request, stream, or event-listener on the session that delivered the originating frame, never on volatile `current`. The delivering session SHALL be carried as a generic per-frame inbound context (frame body plus its origin session) through text dispatch; binary ingress resolves the origin from the stream slot. Request replies, reply-with-stream, stream acks/errors/aborts, subscription acks, and pushed events SHALL target the bound session. If that session is no longer open at send time, the frame SHALL be dropped with the existing INFO log and no exception, because the peer that issued the request is gone.

#### Scenario: Handler replies on the delivering session

- **WHEN** a request arrives on session A, A is adopted, and a reconnect replaces A with B before the handler finishes
- **THEN** the reply is sent on A (or dropped if A is closed), never on B

#### Scenario: Replacement peer never receives a foreign answer

- **WHEN** session B replaces session A and B never issued a given `responseOf`
- **THEN** no `response` or `response-error` frame for that `responseOf` is sent on B

#### Scenario: Pushed event stays on its listener's session

- **WHEN** a subscription created on session A emits an `event`/`listen-ended`/`listen-error` after A closes
- **THEN** the frame is dropped with the INFO log and is not pushed to a replacement session

#### Scenario: Outbound initiation is independent of session binding

- **WHEN** `sendRequest`/`sendNotification`/`sendStream`/`subscribe` is called with no open session
- **THEN** its behavior is unchanged by session binding; offline handling is defined by the `fail-fast-on-missing-session` change (fail fast, no wait)

### Requirement: Fail fast when no session

`RpcChannel` SHALL NOT park outbound sends. When no session is open at send time, `sendRequest`/`sendStream`/`subscribe` SHALL return a future failed with `NoSessionException`, and `sendNotification` SHALL be dropped with a log. Failures SHALL be delivered as an exceptional future, never a synchronous throw. `SESSION_WAIT` and the gate waiters SHALL NOT exist.

#### Scenario: Request fails fast offline

- **WHEN** `sendRequest`/`sendStream`/`subscribe` runs with no open session
- **THEN** its future completes exceptionally with `NoSessionException` naming the requested type, and no frame is transmitted

#### Scenario: Notification drops offline

- **WHEN** `sendNotification` runs with no open session
- **THEN** the notification is dropped with a log and nothing is queued

#### Scenario: Offline is distinguishable from timeout

- **WHEN** a caller inspects the failure cause
- **THEN** `NoSessionException` identifies the offline case, separately from `TimeoutException`

#### Scenario: No synchronous throw

- **WHEN** any outbound method is called with no open session
- **THEN** it returns normally with a failed future (or drops) and does not throw to the caller thread

### Requirement: No outbound buffering across reconnect

The channel SHALL NOT queue or replay outbound frames across a reconnect. A frame produced while no session is open SHALL fail or drop and SHALL NOT be transmitted on a later session. Replies, acks, and pushes produced from an inbound frame SHALL continue to target the delivering session and drop with the INFO log when that session is closed.

#### Scenario: Offline request is not replayed

- **WHEN** a request is issued while no session is open and a new session opens later
- **THEN** no frame for that request is sent on the new session

#### Scenario: Origin-bound frame still targets origin

- **WHEN** a reply/ack/push is produced from a frame delivered on session A and A closes before send
- **THEN** the frame is dropped with the INFO log and is not sent on a replacement session

