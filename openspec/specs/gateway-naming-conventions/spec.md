# gateway-naming-conventions Specification

## Purpose
TBD - created by archiving change rename-gateway-web-vocabulary. Update Purpose after archive.
## Requirements
### Requirement: Web-standard public type naming

The `:gateway` module SHALL name public types using web/RPC vocabulary without redundant or implementation-detail prefixes: the RPC channel SHALL be `RpcChannel` (not `WsRpcChannel`), the WebSocket client SHALL be `WsClient` (not `JdkWsClient`), the handler context SHALL be `RequestContext` (not `InboundContext`), and the byte-stream codec types SHALL be `ChunkWriter`, `ChunkAssembler`, and `ChunkHeader` (not `File*`).

#### Scenario: Channel and client names carry no redundant prefix
- **WHEN** the module's public types are listed
- **THEN** `RpcChannel` and `WsClient` are present and no type named `WsRpcChannel` or `JdkWsClient` remains

#### Scenario: Stream codec types are payload-neutral
- **WHEN** the byte-stream codec types are listed
- **THEN** `ChunkWriter`, `ChunkAssembler`, and `ChunkHeader` are present and no `FileChunkStreamer`, `FileChunkReceiver`, or `FileTransferHeader` remains

### Requirement: Per-area package layout

The `:gateway` module SHALL organize types by responsibility into `gateway.wire`, `gateway.session`, `gateway.rpc`, `gateway.stream`, `gateway.subscription`, `gateway.client`, and `gateway.util`, with each protocol engine (RPC, stream, subscription) in its own area package and shared wire/utility types in `gateway.wire`/`gateway.util`. Test sources SHALL mirror the same package layout.

#### Scenario: Wire types grouped
- **WHEN** wire types are located
- **THEN** `WsMessage`, `WsProtocol`, `StreamStart`, `StreamDone`, `StreamAbort`, `StreamReply`, `SubscriptionPayload`, and `NoSessionException` reside in `gateway.wire`

#### Scenario: Engines separated by area
- **WHEN** protocol engines are located
- **THEN** RPC lives in `gateway.rpc`, stream orchestration in `gateway.stream`, and subscription tracking in `gateway.subscription`

#### Scenario: Tests mirror main
- **WHEN** test sources are located
- **THEN** each test class resides in the same package as the production type it primarily exercises

### Requirement: Subscription vocabulary canonical in code

Java API and type names SHALL use subscription terms: the client operations SHALL be `subscribe`, `unsubscribe`, and `onSubscriptionClose`; the handle SHALL be `SubscriptionHandle`; and the payload SHALL be `SubscriptionPayload`. `WsProtocol` wire-kind constant identifiers SHALL use the `SUBSCRIBE` prefix.

#### Scenario: Client operations use subscribe terms
- **WHEN** the channel's event-stream operations are listed
- **THEN** the methods are named `subscribe`, `unsubscribe`, and `onSubscriptionClose`, and no `listen`, `unlisten`, or `onListenClose` method remains

#### Scenario: Constants renamed without value change
- **WHEN** the event-stream kind constants are inspected
- **THEN** the identifiers use the `SUBSCRIBE` prefix while the string values remain `"listen"`, `"listening"`, `"listen-ended"`, and `"listen-error"`

### Requirement: Wire identifiers frozen

Renaming SHALL NOT change wire output: `WsMessage` field names, wire record component names, `WsProtocol` constant values, and the 20-byte binary chunk header layout SHALL remain byte-identical to before the rename.

#### Scenario: Serialized envelope unchanged
- **WHEN** any frame is serialized after the rename
- **THEN** the JSON keys (`id`, `kind`, `type`, `event`, `payload`, `responseOf`) and stream payload keys (`streamId`, `metadata`, `totalChunks`, `sha256`, `reason`) are unchanged

#### Scenario: Binary chunk header unchanged
- **WHEN** a chunk frame is encoded after the rename
- **THEN** its layout is still a 16-byte transfer id followed by a 4-byte big-endian chunk index, with a 20-byte header

### Requirement: Narrow cross-area bridges with bounded visibility

Cross-area coordination SHALL go through single-purpose interfaces rather than concrete engine types. The stream engine SHALL fail and resolve RPC pending futures through a `PendingRequests` bridge, and visibility SHALL be widened only for the minimum seam set required by the package split.

#### Scenario: Stream settlement uses the bridge
- **WHEN** stream settlement must fail or complete the request it answers
- **THEN** it calls the `PendingRequests` bridge rather than referencing `RpcProtocol` directly

#### Scenario: Duplicated payload conversion removed
- **WHEN** request and stream payload conversion are reviewed
- **THEN** a single shared conversion helper is used by both paths

