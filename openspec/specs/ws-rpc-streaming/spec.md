# ws-rpc-streaming

## Purpose

TBD - created by syncing change add-ws-rpc-send-stream. Update Purpose after implementation.

## Requirements

### Requirement: Stream send with metadata-first framing
The system SHALL provide `WsRpcChannel.sendStream` overloads accepting `ByteBuffer`, `byte[]`, and `InputStream` data plus an opaque metadata object, generating a fresh `streamId`, computing `totalChunks` and SHA-256, transmitting an ordered `start` text frame (type, metadata, `streamId`, `totalChunks`, `sha256`) followed by N binary chunk frames reusing the 20-byte `FileTransferHeader` (`streamId` + `chunkIndex`, payload at most 64KB) followed by a `done` text frame, and returning a `CompletableFuture` that completes with the receiver handler's typed return value.

#### Scenario: ByteBuffer stream round-trip with typed ack
- **WHEN** a sender calls `sendStream` with a type, metadata, and `ByteBuffer` payload while a session is open and the receiver registered a matching stream handler
- **THEN** the receiver handler runs once with the deserialized metadata and assembled bytes and the sender future completes with the converted typed result

#### Scenario: InputStream sender is chunked inside the channel
- **WHEN** a sender passes an `InputStream`
- **THEN** the channel consumes, digests, and chunks it into 64KB frames without requiring the caller to pre-chunk, and the `start` frame carries the computed `totalChunks` and `sha256`

#### Scenario: Stream waits for session like sendRequest
- **WHEN** `sendStream` is invoked with no open session
- **THEN** the channel suspends without failing and transmits `start`/chunks/`done` in order once a session opens within the session-wait limit, else the future fails with `TimeoutException`

### Requirement: Stream receive, integrity, and cap
The system SHALL reassemble inbound binary frames per `streamId` in `WsRpcChannel.onBinaryMessage`, and on `done` verify contiguous chunk indexes against `totalChunks`, enforce a max-bytes cap, and verify SHA-256 before dispatching the stream handler; any integrity, cap, contiguity, or unknown-stream failure SHALL fail the sender ack exceptionally and discard buffers without dispatching the handler.

#### Scenario: Successful assemble and dispatch
- **WHEN** all chunks for a `streamId` arrive followed by a matching `done` with correct SHA-256 and size within cap
- **THEN** the channel assembles bytes in index order, runs the registered handler, and completes the sender ack with the handler result

#### Scenario: Checksum mismatch fails loud
- **WHEN** the assembled SHA-256 differs from `done.sha256`
- **THEN** the channel discards the buffers, never runs the handler, and fails the sender future with an integrity error

#### Scenario: Oversize stream rejected
- **WHEN** assembled bytes would exceed the max-bytes cap
- **THEN** the channel discards the buffers, never runs the handler, and fails the sender future with a cap error

#### Scenario: Unknown stream chunk dropped
- **WHEN** a binary frame arrives for a `streamId` with no reserved slot
- **THEN** the frame is dropped with a log and no handler runs and no pending stream is affected

### Requirement: Stream lifecycle, timeout, abort, and close
The system SHALL bound every stream by the channel op timeout from send, bound receiver slots by a 60 s sliding timeout refreshed on every stream frame with a 300 s absolute ceiling from slot reserve, provide explicit abort that notifies the peer and fails the ack while discarding buffers, fail all pending stream futures and clear receiver slots on `onClose`/`shutdown`, and never silently swallow stream errors.

#### Scenario: Stream timeout
- **WHEN** a stream does not reach terminal `done-ack` within the op timeout
- **THEN** the sender future fails with `TimeoutException` and sender/receiver slots are removed

#### Scenario: Close fails pending streams
- **WHEN** `onClose` or `shutdown` happens with streams in flight
- **THEN** each pending stream future fails with the close cause and receiver buffers are cleared

#### Scenario: Multiplexed streams stay isolated
- **WHEN** two streams with different `streamId` values interleave chunks
- **THEN** each assembles only its own indexed chunks and each sender ack completes with its own handler result

### Requirement: Wire abort notification for streams
Streams SHALL support the `stream-abort` fire-and-forget notification: aborting a live stream discards its slot and buffers, settles the waiter with the reason, and emits a notification to the peer when a session is open. No reply is ever sent for an abort.

#### Scenario: Abort notifies and settles
- **WHEN** a live stream is aborted while a session is open
- **THEN** the peer receives a `stream-abort` notification and the local waiter settles with the reason

#### Scenario: Inbound abort never replies
- **WHEN** a `stream-abort` notification arrives for a live stream
- **THEN** the slot is discarded with no reply frame

### Requirement: Sliding slot timeout refreshed per frame
Receiver slots SHALL refresh their 60 s timeout on every stream frame, bounded by a 300 s absolute ceiling from slot reserve.

#### Scenario: Progress keeps the slot alive
- **WHEN** chunks arrive steadily within each 60 s window
- **THEN** the slot stays live until `done` or the 300 s ceiling

### Requirement: Out-of-range chunk fails the stream
A chunk index outside `0..totalChunks-1` SHALL discard the slot and fail the sender immediately instead of waiting for `done`.

#### Scenario: Bad index fails fast
- **WHEN** a chunk arrives with an index at or above `totalChunks`
- **THEN** buffers are discarded and the sender future fails
