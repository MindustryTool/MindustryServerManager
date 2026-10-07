## ADDED Requirements

### Requirement: Single connection identity with idempotent teardown

`JdkWsClient` SHALL track the live connection through exactly one `Transport` reference. It SHALL NOT keep parallel `webSocket`, generation, or session-identity fields. Teardown of a connection SHALL be idempotent: repeated drop, kick, or close signals for the same or a superseded transport SHALL be ignored.

#### Scenario: One live transport

- **WHEN** a connection is open
- **THEN** the client exposes exactly one current transport and no separate generation or socket identity is consulted

#### Scenario: Teardown runs once

- **WHEN** a transport drops and a late signal for the same transport arrives
- **THEN** channel close, callbacks, queue clear, and reconnect scheduling run only for the first signal

#### Scenario: Superseded transport signal ignored

- **WHEN** a signal arrives for a transport that is no longer current
- **THEN** the live transport and its policy are unaffected

### Requirement: Connection state machine

`JdkWsClient` SHALL model connection policy as a state machine over exactly `IDLE`, `CONNECTING`, `OPEN`, `RECONNECT_WAIT`, `KICKED`, `CLOSED`. All transitions SHALL run through one synchronized transition path that performs side effects (start or stop ping, schedule reconnect, notify the channel). `isOpen()` SHALL be true only in `OPEN`.

#### Scenario: Connect from idle

- **WHEN** `connect()` is called in `IDLE`
- **THEN** the state becomes `CONNECTING` and a dial starts

#### Scenario: Drop enters reconnect wait

- **WHEN** an open socket drops with a code other than `4234`
- **THEN** the state becomes `RECONNECT_WAIT` and a backoff reconnect is scheduled

#### Scenario: Kick enters kicked

- **WHEN** the socket closes with code `4234`
- **THEN** the state becomes `KICKED` and no reconnect is scheduled

#### Scenario: Close is terminal

- **WHEN** `close()` is called from any state
- **THEN** the state becomes `CLOSED`, the scheduler stops, and later `connect()` does not redial

#### Scenario: Manual connect from kicked dials immediately

- **WHEN** `connect()` is called in `KICKED`
- **THEN** the kick clears and a dial starts without waiting out backoff

### Requirement: Injectable transport

`JdkWsClient` SHALL obtain its transport through a builder-configurable factory so substitutes can be supplied without production test seams. The client SHALL NOT expose methods whose only purpose is test manipulation of connection state.

#### Scenario: Factory supplies transport

- **WHEN** the builder is given a transport factory
- **THEN** the client creates its connection through that factory

#### Scenario: No test-only connection methods

- **WHEN** the client public surface is inspected
- **THEN** it contains no `setTestWebSocket` or remote-close simulation method

### Requirement: Channel-only session access

`JdkWsClient` SHALL NOT expose a session accessor or session-wait API. Session truth and waiting SHALL be read from `WsRpcChannel` via `getSession()` and `awaitSession(timeout)`.

#### Scenario: No client session accessor

- **WHEN** the client public surface is inspected
- **THEN** it contains no `session()` or `awaitSession` method

#### Scenario: Caller waits on the channel

- **WHEN** a caller needs an open session
- **THEN** it calls `WsRpcChannel.awaitSession(timeout)` and receives the session or a timeout
