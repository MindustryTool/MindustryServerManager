## ADDED Requirements

### Requirement: Binary Chunked File Download
The Server Manager SHALL support streaming file contents (maps, mods, save files) to the Backend API over the WSS connection by dividing files into binary chunks no larger than 64KB, prefixing each frame with a 20-byte binary header containing the 16-byte UUID `transferId` and 4-byte integer `chunkIndex`.

#### Scenario: Successful file download transfer
- **WHEN** Backend requests a file download via RPC `download-file`
- **THEN** Server Manager sends a `file-transfer-start` message, streams binary chunk frames, and concludes with a `file-transfer-complete` message containing the SHA-256 checksum

#### Scenario: Non-existent file download request
- **WHEN** Backend requests a non-existent file path
- **THEN** Server Manager rejects the request with an RPC error message

### Requirement: Binary Chunked File Upload
The Server Manager SHALL accept incoming file upload streams from the Backend API over the WSS connection, reassemble binary chunks matching the session `transferId`, verify the final checksum upon receiving `file-transfer-complete`, and persist the file to the target container path.

#### Scenario: Successful file upload transfer
- **WHEN** Backend transmits a `file-transfer-start` message followed by binary chunk frames and `file-transfer-complete`
- **THEN** Server Manager saves the reassembled file at the requested destination path and returns an upload confirmation

#### Scenario: Checksum mismatch on file upload
- **WHEN** the reassembled file payload fails checksum verification
- **THEN** Server Manager discards the temporary file buffer and returns an error response
