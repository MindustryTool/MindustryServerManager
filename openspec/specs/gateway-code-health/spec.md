# gateway-code-health

## Purpose

Internal structural requirements for the `:gateway` module produced by the cleanup pass: single helpers for pending-request lifecycle, stream-failure settlement, and close-cause unwrapping; no unreferenced internal members; reused message and hash builders. Synced from change cleanup-gateway-module. Extended by the channel split: RPC/stream/subscription areas own their state behind narrow bridges, wire types are top-level, and comments are contract-only. Synced from change split-ws-rpc-channel.

## Requirements

### Requirement: Single pending-request lifecycle helper

The `:gateway` module SHALL manage pending RPC and stream futures through one helper that removes the future and its timeout task and completes it exceptionally, so the take-and-cancel sequence is defined once.

#### Scenario: All pending removal paths share the helper
- **WHEN** an RPC request times out, a stream times out, an inbound response is handled, or a handler error is settled
- **THEN** the pending future and its timeout entry are removed through the same helper

#### Scenario: Reply-stream failure reuses the helper
- **WHEN** a reply stream fails integrity, cap, or bounds checks
- **THEN** the answered request future is failed through the same helper used for plain request failures

### Requirement: Single stream-failure settlement path

`RpcChannel` SHALL settle a failed stream slot through one helper that chooses local request failure for reply streams and a wire error frame for receiver streams, so the branch is not repeated per failure cause.

#### Scenario: Every stream failure cause uses one settlement
- **WHEN** a stream fails on checksum header mismatch, assembled checksum mismatch, incomplete chunk set, or byte cap
- **THEN** the outcome is produced by the single settlement helper with no per-cause duplication

### Requirement: Shared close-cause unwrapping

The `:gateway` module SHALL provide one helper that unwraps `ExecutionException` and `CompletionException` to their root cause, reused by both the client lifecycle and the transport.

#### Scenario: WebSocketConnection and client unwrap identically
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

### Requirement: Three-way protocol split with pushed-down state

`RpcChannel` SHALL be split so RPC dispatch, stream orchestration, and subscription tracking each own the state they use, while the channel remains a facade owning the session gate, the ordered ingress lane, and `failAll` fan-out order.

#### Scenario: State lives with its consumer
- **WHEN** the split is complete
- **THEN** pending-request maps live with RPC dispatch, slot and timeout maps live with stream orchestration, and subscription maps live with subscription tracking — with no shared grab-bag context object

#### Scenario: Lane and fan-out stay on the facade
- **WHEN** text and binary frames arrive, or the session closes
- **THEN** all inbound frames still serialize through the single channel ingress lane, and close teardown still fans out in the existing order

### Requirement: Top-level wire types with updated call sites

Public wire records (`StreamStart`, `StreamDone`, `StreamAbort`, `StreamReply`, `SubscribePayload`, `SubscriptionRequest`, `SubscriptionHandle`) and protocol constants SHALL live as top-level types rather than nested in `RpcChannel`, and every in-repo reference SHALL be updated; wire bytes SHALL be unchanged.

#### Scenario: No nested-type references remain
- **WHEN** the module and its callers are compiled and searched
- **THEN** no `RpcChannel.X` nested-type reference remains in production or test code

#### Scenario: Wire output identical
- **WHEN** the gateway test suite runs
- **THEN** all wire-shape assertions pass with only import and reference updates

### Requirement: Narrow bridges instead of shared fields

Cross-area coordination SHALL go through explicit single-purpose seams: a reply sink failing RPC pending futures from stream settlement, and a frame sink sending messages with the session-open check.

#### Scenario: Reply-stream bridge is explicit
- **WHEN** a stream failure must fail the request it was answering
- **THEN** settlement calls the reply sink rather than touching a shared pending map

#### Scenario: Error emission is injected
- **WHEN** any area emits an error or reply frame
- **THEN** it uses the injected frame sink with the same open-session guard as before

### Requirement: Contract-only comments

`gateway` split code SHALL keep only comments that state what the code does not: threading and ordering contracts, timeout semantics, fail-loud rules, and `@param`/`@throws` obligations. Restatements and obsolete section banners SHALL be removed, including in public Javadoc.

#### Scenario: No echo comments
- **WHEN** the split code is reviewed
- **THEN** no comment merely restates the adjacent statement or signature

#### Scenario: Contracts preserved
- **WHEN** the split code is reviewed
- **THEN** ordering, timeout, fail-loud, and exception obligations remain documented where they are non-obvious

### Requirement: Test edits limited to shape, not assertions

Test changes for this split SHALL be limited to imports, moved-type references, and reflection targets; no behavior assertion, timeout value, or wire-shape expectation SHALL change.

#### Scenario: Suite guards the refactor
- **WHEN** the full `:gateway:test` suite runs after the split
- **THEN** every test passes with identical assertions, proving behavior and I/O unchanged
