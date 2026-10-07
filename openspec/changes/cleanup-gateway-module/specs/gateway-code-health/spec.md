## ADDED Requirements

### Requirement: Single pending-request lifecycle helper

The `:gateway` module SHALL manage pending RPC and stream futures through one helper that removes the future and its timeout task and completes it exceptionally, so the take-and-cancel sequence is defined once.

#### Scenario: All pending removal paths share the helper
- **WHEN** an RPC request times out, a stream times out, an inbound response is handled, or a handler error is settled
- **THEN** the pending future and its timeout entry are removed through the same helper

#### Scenario: Reply-stream failure reuses the helper
- **WHEN** a reply stream fails integrity, cap, or bounds checks
- **THEN** the answered request future is failed through the same helper used for plain request failures

### Requirement: Single stream-failure settlement path

`WsRpcChannel` SHALL settle a failed stream slot through one helper that chooses local request failure for reply streams and a wire error frame for receiver streams, so the branch is not repeated per failure cause.

#### Scenario: Every stream failure cause uses one settlement
- **WHEN** a stream fails on checksum header mismatch, assembled checksum mismatch, incomplete chunk set, or byte cap
- **THEN** the outcome is produced by the single settlement helper with no per-cause duplication

### Requirement: Shared close-cause unwrapping

The `:gateway` module SHALL provide one helper that unwraps `ExecutionException` and `CompletionException` to their root cause, reused by both the client lifecycle and the transport.

#### Scenario: Transport and client unwrap identically
- **WHEN** a send or dial future fails with a wrapped cause
- **THEN** the client and transport report the same root cause for equivalent wrapped failures

### Requirement: No unreferenced internal members

The `:gateway` module SHALL NOT retain private or package-private members that are never referenced by production code.

#### Scenario: Dead members removed
- **WHEN** the module is compiled and searched for references
- **THEN** the previously unused package-private backoff helper, transport termination/sender predicates, the unused transport constructor, the unused receiver chunk-count method, and the unused stream-handler response-type field are absent

### Requirement: Reused builders for messages and hashes

The `:gateway` module SHALL build equivalent RPC reply/error frames and stream start/done envelopes through shared builders, and SHALL compute SHA-256 through a single helper rather than two copies.

#### Scenario: One SHA-256 helper
- **WHEN** stream framing or stream reassembly computes a digest
- **THEN** both call the same SHA-256 utility

#### Scenario: One reply-frame builder
- **WHEN** the channel emits an error frame or a stream ack
- **THEN** both are produced by a shared message builder

### Requirement: Behavior preservation

The refactor SHALL NOT change any observable gateway behavior.

#### Scenario: Existing suite passes unchanged
- **WHEN** the gateway test suite is run after the cleanup
- **THEN** all existing tests pass without modification

#### Scenario: Public API unchanged
- **WHEN** external modules compile against the cleaned module
- **THEN** no public type, method, or parameter signature used by callers has changed
