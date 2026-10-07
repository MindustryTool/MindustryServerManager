## ADDED Requirements

### Requirement: Overwrite on open with 4234 kick

The system SHALL let `WsRpcChannel.onOpen(newSession)` replace any live session: when a different open session exists it is closed with code `4234` and reason naming the replacement, then the new session becomes current. Null or non-open sessions SHALL still throw. Late `onClose` for a non-current session SHALL be ignored.

#### Scenario: Second open replaces first

- **WHEN** `onOpen(B)` runs while current session `A` is open and `B != A`
- **THEN** `A.close(4234, reason)` is invoked and current becomes `B` with waiters woken

#### Scenario: Same-session open is a no-op success

- **WHEN** `onOpen` receives the session already current
- **THEN** current is unchanged and no close is emitted

#### Scenario: Null or closed session still throws

- **WHEN** `onOpen` receives null or a session reporting not open
- **THEN** it throws without changing current

#### Scenario: Stale close ignored

- **WHEN** `onClose(A)` runs while current is `B`
- **THEN** current stays `B` and no waiters or pending work are failed

### Requirement: Gate wait without stored future

The system SHALL hold no completed-or-failed session future across closes. Sends read volatile current live; when no session is open they park on a gate until an open arrives or `SESSION_WAIT` (5 minutes) expires.

#### Scenario: Send waits when no session

- **WHEN** `sendRequest`/`sendNotification`/`sendStream`/`subscribe` runs with no open session
- **THEN** it waits up to `SESSION_WAIT` and transmits on the first open instead of failing with `IllegalStateException`

#### Scenario: Wait expiry fails with timeout

- **WHEN** no open session appears within `SESSION_WAIT`
- **THEN** the waiter completes exceptionally with `TimeoutException` (notifications drop with a log)

### Requirement: Fail-all on close

The system SHALL fail all queued gate waiters, live RPC futures, live stream futures, and client/server subscription slots when the current session closes (non-stale close or shutdown). New sends after the close wait fresh.

#### Scenario: Close fails waiters and live work

- **WHEN** the current session closes
- **THEN** queued waits and live request/stream futures fail with the close cause and subscription close callbacks run

#### Scenario: Post-close sends wait fresh

- **WHEN** a send starts after a close with no session open
- **THEN** it waits up to `SESSION_WAIT` for the next open

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
