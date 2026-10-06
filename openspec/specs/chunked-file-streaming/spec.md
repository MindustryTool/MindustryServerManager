# chunked-file-streaming

## Purpose

Binary frame chunked file transfer protocol over WebSocket for uploading and downloading maps, mods, and save backups. Synced from change wss-backend-communication.

## Requirements

### Requirement: Binary Chunked File Download
The Server Manager SHALL answer Backend `download-file` requests with a reply-with-stream: the `stream-start`/`stream-done` envelopes carry `responseOf` equal to the request `id`, binary chunks are no larger than 64KB prefixed with a 20-byte binary header containing the 16-byte UUID `streamId` and 4-byte integer `chunkIndex`, and the requester's future resolves with the file bytes after SHA-256 verification instead of a separate summary.

#### Scenario: Successful file download transfer
- **WHEN** Backend requests a file download via RPC `download-file`
- **THEN** Server Manager streams a `start` message, binary chunk frames, and a `done` message as the request reply, and the request resolves with the file bytes

#### Scenario: Non-existent file download request
- **WHEN** Backend requests a non-existent file path
- **THEN** Server Manager rejects the request with an RPC error message

### Requirement: Binary Chunked File Upload
The Server Manager SHALL accept incoming file upload streams from the Backend API over the WSS connection through a registered `WsRpcChannel` stream handler, reassemble binary chunks matching the session `streamId`, enforce contiguity, max-bytes cap, and checksum verification upon receiving `done`, and persist the file to the target container path.

#### Scenario: Successful file upload transfer
- **WHEN** Backend transmits a `start` message followed by binary chunk frames and `done`
- **THEN** Server Manager saves the reassembled file at the requested destination path and returns an upload confirmation via the stream ack

#### Scenario: Checksum mismatch on file upload
- **WHEN** the reassembled file payload fails checksum verification
- **THEN** Server Manager discards the temporary file buffer and returns an error response

### Requirement: File transfers inherit abort and sliding timeout
File upload and download streams SHALL support `stream-abort` notifications and the sliding 60 s slot timeout with a 300 s ceiling. An aborted transfer SHALL discard buffered chunks without persisting a partial file.

#### Scenario: Abort cancels an upload
- **WHEN** either peer aborts a live `file-upload` stream
- **THEN** buffered chunks are discarded, no partial file is persisted, and no reply is sent for the abort itself

#### Scenario: Slow download survives on progress
- **WHEN** download chunks arrive steadily within each 60 s window
- **THEN** the transfer stays live until `done` or the 300 s ceiling
