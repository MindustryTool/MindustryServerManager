# transport-send-commands

## Purpose

Internal send dispatch contract for Transport — command types, context, control split, ordering, and error mapping. Synced from change invert-sendop-to-command.

## Requirements

### Requirement: Sealed send commands replace Kind switch
`Transport` SHALL expose `SendOp` as a sealed interface in `gateway.client` with record impls `TextOp`, `BinaryOp`, `PingOp`, `CloseOp`, and `senderLoop` SHALL dispatch via `op.execute(ctx)` with no `Kind` switch for data sends.

#### Scenario: Text dispatch without switch
- **WHEN** `sendText("hi")` enqueues a command
- **THEN** the sender executes a `TextOp` that calls `sendText` on the live socket with the same payload and order is preserved

#### Scenario: Binary dispatch with owned bytes
- **WHEN** `sendBinary(buffer)` is called
- **THEN** the caller thread copies remaining bytes and the sender executes a `BinaryOp` with those exact bytes

#### Scenario: Ping payload generated at creation
- **WHEN** the ping task creates a ping command
- **THEN** `PingOp` carries an 8-byte payload made in its factory

### Requirement: Per-send context with injectable timeout
`Transport` SHALL take send timeout via ctor (default 10s preserved) and build a per-send `SendContext` holding live socket, timeout, and events for `execute`.

#### Scenario: Timeout applied from ctor
- **WHEN** `Transport` is built with a custom send timeout
- **THEN** all frame `get(timeout)` calls use that value instead of a static const

#### Scenario: Close uses live socket
- **WHEN** a `CloseOp(code, reason)` executes
- **THEN** it resolves the current live socket at execute time and sends close with `reason == null` mapped to `""`

### Requirement: Control split from data on one queue
The send queue SHALL be `BlockingQueue<QueueItem>` where `QueueItem` permits `SendOp` (data) and `Control` (lifecycle `POISON` / terminate); the sender SHALL check `Control` first and return without socket use.

#### Scenario: Poison stops sender
- **WHEN** `terminate()` enqueues control after clearing data
- **THEN** the sender drains to the control item and exits without sending further frames

#### Scenario: Graceful close then stop
- **WHEN** `shutdownGracefully(code, reason)` runs with an open socket
- **THEN** it enqueues `CloseOp` then control, the sender emits the close frame once and exits

### Requirement: Single-owned error mapping and ordering
`SendOp.execute` SHALL throw on failure and never call `Events` directly; `senderLoop` SHALL catch, log, and call `onTransportError(rootCause)` once per failed frame while preserving FIFO order and queue-depth warning behavior.

#### Scenario: Failed text maps once
- **WHEN** a `TextOp` send fails
- **THEN** the sender calls `onTransportError` with the root cause and continues with the next item

#### Scenario: Terminated transport rejects enqueue
- **WHEN** `enqueue` is called after termination or with no open socket
- **THEN** it throws `IllegalStateException` and enqueues nothing
