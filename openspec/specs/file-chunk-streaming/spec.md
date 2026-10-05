# file-chunk-streaming

## Purpose

TBD - created by syncing change extract-gateway-subproject. Update Purpose after implementation.

## Requirements

### Requirement: Binary File Chunk Framing
The `:gateway` module SHALL provide `FileChunkStreamer` and `FileChunkReceiver` that encode and decode file chunks into binary WebSocket frames prefixed with a 20-byte header containing a 16-byte UUID `transferId` and a 4-byte big-endian `chunkIndex`.

#### Scenario: Encoding file chunk frame
- **WHEN** `FileChunkStreamer` generates a binary chunk frame
- **THEN** the first 16 bytes contain the session UUID and the next 4 bytes contain the integer chunk index, followed by the raw chunk bytes (maximum 64KB)

#### Scenario: Decoding file chunk frame
- **WHEN** `FileChunkReceiver` receives a valid binary frame
- **THEN** it correctly parses the 16-byte transfer ID, 4-byte chunk index, and appends the payload bytes to the corresponding transfer buffer

### Requirement: File Checksum Verification
The `:gateway` module SHALL calculate and verify SHA-256 checksums upon completing a chunked file transfer, confirming data integrity before finalizing the transfer.

#### Scenario: Checksum matches
- **WHEN** all chunks are received and the computed SHA-256 matches the expected checksum
- **THEN** the file transfer future completes successfully

#### Scenario: Checksum mismatch
- **WHEN** the computed SHA-256 differs from the expected checksum
- **THEN** the transfer is aborted with an integrity error and temporary buffers are discarded
