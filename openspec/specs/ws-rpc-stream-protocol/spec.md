# ws-rpc-stream-protocol

## Purpose

Versioned peer wire protocol for streams (control envelopes, chunk framing, integrity, ack/error correlation, lifecycle constants, file-upload / download-file flows).

## Requirements

### Requirement: Stream control envelopes
The system SHALL frame every stream as one `stream-start` text frame, N binary chunk frames, and one `stream-done` text frame on the same WebSocket in emission order. `stream-start` and `stream-done` are reserved types and SHALL NOT be used as application stream types. `stream-start` payload SHALL contain `streamId` (UUID string), `streamType` (string), `metadata` (any JSON, nullable), `totalChunks` (integer ≥ 1), and `sha256` (lowercase hex). `stream-done` payload SHALL contain `streamId` and `sha256`.

#### Scenario: Ordered start-chunks-done emission
- **WHEN** a sender emits a stream
- **THEN** the peer observes `stream-start`, then all `totalChunks` binary frames, then `stream-done`, in that order

#### Scenario: Empty payload still sends one chunk
- **WHEN** the stream payload is empty
- **THEN** the sender emits exactly one zero-length binary chunk with `totalChunks` equal to 1

### Requirement: Binary chunk framing
Each binary frame SHALL carry a 20-byte header (16-byte UUID `streamId` as big-endian uint64 pair, 4-byte big-endian `chunkIndex`) followed by at most 64 KiB of payload, with `chunkIndex` values ascending from 0 without gaps.

#### Scenario: Valid chunk ingested
- **WHEN** a binary frame with a correct 20-byte header arrives for an announced stream
- **THEN** its payload is buffered under its `chunkIndex`

#### Scenario: Short frame dropped
- **WHEN** a binary frame shorter than 20 bytes arrives
- **THEN** it is dropped with a log and no reply is sent

### Requirement: Stream integrity and cap
The receiver SHALL verify contiguous indexes against `totalChunks`, enforce a 32 MiB max-bytes cap incrementally and finally, and verify SHA-256 over concatenated chunks before dispatching. Any integrity, cap, contiguity, or unknown-stream failure SHALL fail the sender with an error frame and discard buffers without dispatching.

#### Scenario: Checksum mismatch fails loud
- **WHEN** the assembled SHA-256 differs from the envelope value
- **THEN** buffers are discarded and the sender receives an error frame

#### Scenario: Oversize stream rejected
- **WHEN** received bytes exceed 32 MiB
- **THEN** the slot and buffers are discarded and the sender receives an error frame

#### Scenario: Unknown stream chunk dropped
- **WHEN** a chunk arrives for an unannounced `streamId`
- **THEN** it is dropped with a log and no reply is sent

### Requirement: Ack and error correlation
Every stream SHALL be answered exactly once: success frames carry `responseOf` equal to the `stream-start` frame `id` with `error:false`; failure frames carry `responseOf` equal to the answered frame `id` with `error:true` and a string detail payload. Frames carrying `responseOf` SHALL never trigger further replies.

#### Scenario: Ack correlates to start id
- **WHEN** a stream completes successfully
- **THEN** the sender receives one frame with `responseOf` equal to the `stream-start` `id`

#### Scenario: Unknown stream type fails fast
- **WHEN** a `stream-start` arrives for an unregistered `streamType`
- **THEN** the sender receives an `error:true` frame naming the unknown type and no slot is reserved

### Requirement: Stream lifecycle constants
Senders SHALL bound streams by a 60 s op timeout, receivers SHALL bound open slots by a 60 s timer, senders SHALL wait up to 300 s for a session, and connection close SHALL fail pending senders and clear receiver slots.

#### Scenario: Stream timeout
- **WHEN** a stream does not complete within the op timeout
- **THEN** the sender future fails and both sides discard stream state

#### Scenario: Close clears streams
- **WHEN** the connection closes with streams in flight
- **THEN** pending senders fail with the close cause and receiver buffers are cleared

### Requirement: Reply-with-stream
An RPC handler MAY answer its incoming request with a stream whose `stream-start` and `stream-done` carry `responseOf` equal to the request `id`; the requester's pending future SHALL then resolve with the assembled bytes instead of a separate ack.

#### Scenario: Download resolves with bytes
- **WHEN** a `download-file` request is answered with a reply stream
- **THEN** the requester's future resolves with the file bytes and no separate stream handler is needed

### Requirement: File upload and download flows
Uploads SHALL use stream type `file-upload` with metadata `{"serverId","path"}` and result `{"bytes","sha256"}`. Downloads SHALL use the `download-file` request with `{"serverId","path"}` answered by a reply-with-stream resolving with the file bytes.

#### Scenario: Successful upload
- **WHEN** a peer streams `file-upload` with valid metadata and intact chunks
- **THEN** the file is persisted and the sender receives `{"bytes","sha256"}`

#### Scenario: Successful download
- **WHEN** a peer requests `download-file` for an existing file
- **THEN** the request resolves with the file bytes after integrity verification

### Requirement: Stream abort wire protocol
The system SHALL handle inbound `stream-abort` notifications per WS-RPC Section 7.7: discard the slot and buffered chunks, settle local waiters with the reason, and never send a reply. Aborts for unknown streams SHALL be dropped with a log. The first settler wins: a late abort or a late answer is dropped, never double-applied. `stream-abort` SHALL be a reserved type alongside `stream-start` and `stream-done`, never usable as an application stream type.

#### Scenario: Abort discards slot with no reply
- **WHEN** a `stream-abort` notification arrives for a live stream
- **THEN** the slot and buffered chunks are discarded, the waiter settles with the reason, and no frame is transmitted back

#### Scenario: Abort for unknown stream dropped
- **WHEN** a `stream-abort` arrives for an unannounced `streamId`
- **THEN** it is dropped with a log and no reply is sent

#### Scenario: Abort type cannot be registered
- **WHEN** code registers a request or stream handler with type `stream-abort`
- **THEN** the call throws `IllegalArgumentException`

### Requirement: Sliding slot timeout with ceiling
The receiver SHALL refresh the 60 s slot timeout on every `start`, chunk, or `done` frame for the stream, bounded by an absolute ceiling of 300 s from slot reserve. A slot whose `done` never arrives SHALL be discarded after the timeout and the sender SHALL be answered with an error if still reachable.

#### Scenario: Active stream survives past 60 s
- **WHEN** chunks keep arriving within each 60 s window and the slot is younger than 300 s
- **THEN** the slot stays live until `done` or the ceiling

#### Scenario: Idle slot times out
- **WHEN** no stream frame arrives within 60 s of the last one
- **THEN** the slot and buffers are discarded and the sender receives an error frame

#### Scenario: Ceiling bounds long streams
- **WHEN** a slot reaches 300 s from reserve without completing
- **THEN** it is discarded even if frames are still arriving

### Requirement: Out-of-range chunk index fails fast
A chunk with index outside `0..totalChunks-1` SHALL be treated as a stream failure: the system SHALL discard the slot and buffers and answer with an error (normal streams) or fail the request future locally with no frame (reply streams).

#### Scenario: High index rejected at ingest
- **WHEN** a chunk arrives with `chunkIndex` equal to or above `totalChunks`
- **THEN** the slot is discarded and the sender fails with an error without waiting for `done`
