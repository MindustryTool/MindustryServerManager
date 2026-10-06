## ADDED Requirements

### Requirement: Socket-closed definition

The system SHALL treat a gateway client as not connected when `rpcChannel.getSession()` is `null` or the session reports not open.

#### Scenario: Null session counts as closed

- **WHEN** no session has been opened or the channel has been closed
- **THEN** the client counts as not connected

#### Scenario: Closed session counts as closed

- **WHEN** a session exists but `isOpen()` returns false
- **THEN** the client counts as not connected

#### Scenario: Open session counts as connected

- **WHEN** a session exists and `isOpen()` returns true
- **THEN** the client counts as connected

### Requirement: Disconnect-age clock lifecycle

The system SHALL maintain `lastDisconnectAt` per gateway client: set to create time at build, cleared to `null` on open, set to current time on close.

#### Scenario: Never-linked client carries create time

- **WHEN** a client is created via `of(id)` and no open has occurred
- **THEN** `lastDisconnectAt` equals create time

#### Scenario: Open clears the clock

- **WHEN** `onOpen` succeeds
- **THEN** `lastDisconnectAt` is `null`

#### Scenario: Close starts the clock

- **WHEN** `onClose` runs
- **THEN** `lastDisconnectAt` is set to close time

#### Scenario: Reconnect restarts grace

- **WHEN** a client reopens after a disconnect
- **THEN** the prior disconnect time is discarded and warn and kill use the new cycle

### Requirement: Disconnect warn

The system SHALL log a socket-disconnected warning when a client has `lastDisconnectAt != null`, current time is after `lastDisconnectAt` plus 60 seconds, and the socket is still closed. No Docker running check applies.

#### Scenario: Closed past 60s warns

- **WHEN** a client stays socket-closed for more than 60 seconds
- **THEN** the system emits a socket-disconnected warning

#### Scenario: Connected never warns

- **WHEN** the socket is open, even with no recent app messages
- **THEN** no warning is emitted

#### Scenario: Fresh disconnect stays quiet

- **WHEN** the socket closed less than 60 seconds ago
- **THEN** no warning is emitted

### Requirement: Disconnect terminate

The system SHALL terminate a gateway client with reason `NOT_CONNECTED` when `lastDisconnectAt != null`, current time is after `lastDisconnectAt` plus 3 minutes, and the socket is still closed. No Docker running check applies.

#### Scenario: Closed past 3min terminates

- **WHEN** a client stays socket-closed for more than 3 minutes
- **THEN** the system terminates it with `NOT_CONNECTED` and removes it from the client map

#### Scenario: Connected never terminates

- **WHEN** the socket is open at check time
- **THEN** no termination occurs, even if no app messages arrived for more than 3 minutes

#### Scenario: Never-linked client terminates after 3min

- **WHEN** a client never opened and its create time is more than 3 minutes ago with no open session
- **THEN** the system terminates it with `NOT_CONNECTED`

#### Scenario: Reconnected client survives old clock

- **WHEN** a client disconnected, reconnected, and the new session is still open at 3min past the old close
- **THEN** no termination occurs
