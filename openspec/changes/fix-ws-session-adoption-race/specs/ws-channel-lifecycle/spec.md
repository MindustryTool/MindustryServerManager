## ADDED Requirements

### Requirement: Inbound frames dispatch only after adoption

`WsRpcChannel` SHALL dispatch inbound frames only after a session has been adopted. Inbound entry points SHALL carry the delivering session, and the channel SHALL NOT dispatch while no session is adopted. Frames delivered by a transport whose session was never adopted SHALL NOT reach any handler.

#### Scenario: No dispatch while unadopted

- **WHEN** a frame is delivered to a transport before `onOpen` adopts its session
- **THEN** no handler runs and no reply is attempted

#### Scenario: Dispatch after adoption carries the deliverer

- **WHEN** a frame is delivered after its transport is adopted
- **THEN** the delivering session is captured at ingress and carried through dispatch

### Requirement: Replies and pushes bind to the delivering session

`WsRpcChannel` SHALL answer an inbound request, stream, or event-listener on the session that delivered the originating frame, never on volatile `current`. The delivering session SHALL be carried as a generic per-frame inbound context (frame body plus its origin session) through text dispatch; binary ingress resolves the origin from the stream slot. Request replies, reply-with-stream, stream acks/errors/aborts, subscription acks, and pushed events SHALL target the bound session. If that session is no longer open at send time, the frame SHALL be dropped with the existing INFO log and no exception, because the peer that issued the request is gone.

#### Scenario: Handler replies on the delivering session

- **WHEN** a request arrives on session A, A is adopted, and a reconnect replaces A with B before the handler finishes
- **THEN** the reply is sent on A (or dropped if A is closed), never on B

#### Scenario: Replacement peer never receives a foreign answer

- **WHEN** session B replaces session A and B never issued a given `responseOf`
- **THEN** no `response` or `response-error` frame for that `responseOf` is sent on B

#### Scenario: Pushed event stays on its listener's session

- **WHEN** a subscription created on session A emits an `event`/`listen-ended`/`listen-error` after A closes
- **THEN** the frame is dropped with the INFO log and is not pushed to a replacement session

#### Scenario: Outbound initiation still uses current

- **WHEN** `sendRequest`/`sendNotification`/`sendStream`/`subscribe` is called with no open session
- **THEN** it still waits up to `SESSION_WAIT` and transmits on the next open, unchanged by session binding
