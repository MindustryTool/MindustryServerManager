## MODIFIED Requirements

### Requirement: Binary Chunked File Download
The Server Manager SHALL answer Backend `download-file` requests with a reply-with-stream: the `stream-reply-start`/`stream-reply-done` envelopes carry `responseOf` equal to the request `id` and `type` naming the stream handler, binary chunks are no larger than 64KB prefixed with a 20-byte binary header containing the 16-byte UUID `streamId` and 4-byte integer `chunkIndex`, and the requester's future resolves with the file bytes after SHA-256 verification instead of a separate summary.

#### Scenario: Successful file download transfer
- **WHEN** Backend requests a file download via RPC `download-file`
- **THEN** Server Manager streams a `stream-reply-start` frame, binary chunk frames, and a `stream-reply-done` frame as the request reply, and the request resolves with the file bytes

#### Scenario: Non-existent file download request
- **WHEN** Backend requests a non-existent file path
- **THEN** Server Manager rejects the request with a `response-error` message
