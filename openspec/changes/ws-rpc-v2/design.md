## Context

The v1 protocol (`WS-RPC.md` before this change) multiplexes requests, notifications, byte streams, and subscription streams over one WebSocket. Its envelope has `id`, `type`, `payload`, `responseOf`, and `error`. Two fields are overloaded:

- `type` is both a frame kind (`subscribe`, `stream-start`) and an application name (handler, stream type, event topic). A reserved-word list protects the frame-kind values.
- `responseOf` identifies an RPC answer, a reply-with-stream envelope, and a subscription event. The channel disambiguates by probing the subscription table before the pending-call table (`WsRpcChannel.dispatchText`).

v1 is a draft and is not deployed anywhere, so the wire may change freely without negotiation.

## Goals / Non-Goals

**Goals:**
- Make frame routing explicit and total: one `kind` per frame maps to exactly one receiver table.
- Remove the reserved application-name list; any handler, stream type, or event name is legal.
- Model event streams honestly as SSE-like long-lived event sequences, not broker subscriptions.
- Encode failure in the frame kind instead of a boolean.
- Keep the existing stream integrity, cap, timeout, and ordering guarantees.

**Non-Goals:**
- Version negotiation (v1 is undeployed).
- Machine-readable error codes.
- Subscription flow control or backpressure.
- Resuming streams or event streams across connections.
- Changing chunk framing, SHA-256 verification, or the timeout constants.

## Decisions

### 1. Add an explicit `kind` field

Every text frame carries `kind`; a frame with absent or unknown `kind` is dropped with a log and no reply.

Alternatives considered:
- **Namespace `type` with a prefix** (`@stream:start`): smaller wire change, but `type` stays overloaded and the convention still collides.
- **Keep v1 and extend the reserved list**: no wire change, but every new control frame claims more application names.

Rationale: only an explicit discriminator removes the class of bug. Routing becomes `switch (kind)`, which is total and needs no map probing.

### 2. Per-family subject field: `type` vs `event`

RPC and byte-stream frames name their subject with `type` (handler / stream handler). Event-stream frames name theirs with `event` (event name), matching SSE's `event:` field.

Alternatives considered:
- **One universal `type` everywhere**: uniform, but keeps the broker-flavored conflation and hides that event streams are not topics.
- **A dedicated `eventName` field**: explicit, but a third name for the same idea and less SSE-faithful.

Rationale: each family names its subject honestly. The cost is that there is no single universal subject field; this is accepted deliberately.

### 3. Failure encoded in `kind`; drop the `error` flag

`response-error` marks a failed RPC answer; `listen-error` marks a failed or terminated event stream. The `error` boolean is removed.

Alternatives considered:
- **Keep `error` only on answers**: fewer kinds, but the failure marker becomes inconsistent across families.
- **Keep the flag everywhere**: preserves v1 but contradicts the kind-based model.

Rationale: with kind-encoded failures, a receiver branches on one field. `response-error` and `listen-error` are distinct strings, so kind-to-table stays 1:1.

### 4. Dedicated `listening` acknowledgement

A `listen` is answered with `listening`. This makes the pending-to-active transition explicit and separates an ack from the first event.

Alternatives considered:
- **First event as ack (v1)**: fewer frames, but an empty/null event and an accepted-but-silent stream are indistinguishable.
- **No ack**: silent acceptance is indistinguishable from rejection until timeout.

### 5. SSE-aligned event-stream naming

Kinds are `listen`, `listening`, `event`, `unlisten`, `listen-ended`, `listen-error`. The concept is an event stream; the payload parameter field is `data`.

Alternatives considered:
- **Keep `subscribe`/`unsubscribe`/`topic`**: smallest change, but implies broker fan-out, backlog, and replay, none of which exist.
- **`open`/`close`**: SSE-faithful, but overlaps WebSocket connection terminology.

Note: `listen-ended` is used both as the confirmation of `unlisten` and as a publisher-initiated graceful end; `listen-error` covers a rejected `listen` and a mid-stream failure.

### 6. Dedicated reply-stream kinds

Reply-with-stream uses `stream-reply-start` and `stream-reply-done` with `responseOf` equal to the request `id`. An initiating stream uses `stream-start`/`stream-done`.

Alternatives considered:
- **One `stream-start`/`stream-done` pair distinguished by `responseOf` presence**: fewer kinds, but reintroduces field-presence inference — the very thing `kind` removes.

### 7. Routing table

Each kind maps to one table: RPC kinds to the handler registry or pending-call table; byte-stream kinds to the stream handler registry or slot/pending tables; event-stream kinds to the event handler registry or listener table. The receiver MUST NOT probe more than one table.

### 8. Notifications are never answered

A `notification` is processed as a no-op when its `type` is unregistered and is never answered. This differs from a `request`, which is answered with `response-error` when its type is unregistered.

Rationale: a notification declares that no answer is expected; answering it would violate that contract.

### 9. Abort

A single `stream-abort` kind, with `type` supplied at the abort call site. Reply streams are not abortable.

Alternatives considered:
- **Split `stream-abort`/`stream-reply-abort`**: fully self-describing, but forces the reply-stream sender, which keeps no sender-side tracking, to gain one. Rejected.

### 10. Deferred

No version negotiation, no error codes, no flow control. Each is documented as explicitly out of scope so it is a known limit, not an oversight.

## Risks / Trade-offs

- [Breaking wire change] → v1 is undeployed; both peers move to v2 together. No shim.
- [`type`/`event` asymmetry confuses implementers] → the routing table and validation rules (subject field per kind) make the expected field explicit; reject frames that carry the wrong subject field.
- [`listen-ended` means both "ack to unlisten" and "publisher end"] → both mean the stream is over, so a single kind is unambiguous to the listener.
- [`response-error` vs `listen-error` naming drift] → both are kind-encoded failures; the routing table is the single source of truth.
- [Reserved-type removal loses a guard] → kind validation replaces it; an application name equal to a kind value is now legal because `kind` and the subject fields are separate.
- [No flow control] → publishers may buffer unboundedly; the application owns backpressure and drop policy, documented as a limit.

## Migration Plan

1. Rewrite `WS-RPC.md` to v2 (done as part of this change); keep `WS-RPC-v2.md` as the supplement.
2. Update `WsMessage` to add `kind` and `event` and remove `error`.
3. Update `WsProtocol` kind constants; delete `rejectStreamControlType` and its call sites.
4. Update `WsRpcChannel.dispatchText` to `switch (kind)`.
5. Update `RpcProtocol`, `StreamProtocol`, and the event-stream protocol to emit and consume v2 frames.
6. Update server-side handlers and tests.

Rollback: revert the code and restore the v1 `WS-RPC.md`. Because no v1 peer is deployed, no mixed-version state exists.

## Open Questions

- Whether to later add a `hello` handshake and error codes; currently deferred.
- Whether event-stream resumption is ever needed; currently unsupported by design.
