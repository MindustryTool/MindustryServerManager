# WS RPC Protocol (WS-RPC)

Status: Draft — Version 2
Scope: Complete wire protocol between two symmetric peers.
This document is implementation-neutral and application-neutral.
It contains no source code.
JSON examples are wire illustrations, not program code.

## 1. Introduction

WS-RPC multiplexes **four** traffic kinds over one WebSocket connection:

1. Request/response remote calls with typed correlation.
2. One-way notifications.
3. Metadata-first byte streams with integrity verification.
4. **Event streams** — long-lived, server-pushed sequences of JSON
   events per listener (SSE-like).

Either peer may send requests, notifications, streams, or event streams.
Either peer may answer them. The protocol does not define client or
server roles.

Design goals: single connection, explicit correlation, fail-loud
errors, bounded memory, ordered delivery per sender.

Every control frame declares its sort with an explicit `kind` field.
There are no reserved application names: any handler, stream type, or
event name is legal.

## 2. Conventions

The key words MUST, MUST NOT, SHALL, SHALL NOT, SHOULD, MAY follow
RFC 2119.

- UUID: canonical lowercase string form,
  e.g. `123e4567-e89b-12d3-a456-426614174000`.
- HEX: lowercase hexadecimal, e.g. SHA-256 digests.
- INTEGERS: unsigned, big-endian (network byte order) on the wire.
- JSON: UTF-8 encoded text frames. Field order is insignificant.
- Error detail strings are diagnostic only. Peers MUST match on `kind`
  and `responseOf` fields, never on message text.

## 3. Transport

3.1. Peers communicate over one WebSocket connection carrying two
frame kinds: UTF-8 JSON text frames (control) and binary frames
(stream chunks).

3.2. Order: frames MUST be transmitted and processed in call order
per connection. A stream `start` frame MUST never be reordered after
its own chunks. Concurrent streams from one sender interleave freely;
the receiver separates them by stream identifier (Section 8).

3.3. Keepalive: peers MAY use WebSocket ping/pong. Recommended
practice is a ping every 20 seconds, declare the peer dead after
45 seconds without a pong, and reconnect with capped exponential
backoff. Keepalive policy never changes framing.

## 4. Text envelope

Every control frame is one JSON object with these fields:

| Field       | Type        | Required   | Meaning                                        |
|-------------|-------------|------------|------------------------------------------------|
| `id`        | UUID string | yes        | unique per frame; correlation anchor           |
| `kind`      | string      | yes*       | frame kind (*absent or unknown kind is dropped)|
| `type`      | string      | per kind   | application subject for RPC and byte streams   |
| `event`     | string      | per kind   | event name for event streams                   |
| `payload`   | any JSON    | no         | body; failure frames carry a JSON string       |
| `responseOf`| UUID string | per kind   | the frame this one answers or continues        |

4.1. `kind` is the frame discriminator. A frame whose `kind` is absent
or not a known kind is dropped with a log. No reply.

4.2. `type` names the application subject for `request`, `notification`,
`response`, `response-error`, and the byte-stream kinds. It MUST be
absent on event-stream kinds.

4.3. `event` names the event for the event-stream kinds. It MUST be
absent on RPC and byte-stream kinds.

4.4. A frame carrying `responseOf` is an answer or continuation. Answers
MUST NOT trigger further answers, even when malformed.

4.5. An unparsable text frame is dropped with a log. No reply.

4.6. A routing failure is decided by `kind`, never by payload text.

## 5. Routing

The receiver dispatches on `kind` alone. Each kind maps to exactly one
receiver table:

| Kind                | Family       | Receiver state            |
|---------------------|--------------|---------------------------|
| `request`           | RPC          | handler registry          |
| `notification`      | RPC          | handler registry          |
| `response`          | RPC          | pending-call table        |
| `response-error`    | RPC          | pending-call table        |
| `stream-start`      | byte stream  | stream handler registry   |
| `stream-done`       | byte stream  | stream slot table         |
| `stream-abort`      | byte stream  | stream slot / sender map  |
| `stream-reply-start`| byte stream  | pending-call table        |
| `stream-reply-done` | byte stream  | pending-call table        |
| `listen`            | event stream | event handler registry    |
| `listening`         | event stream | listener table            |
| `event`             | event stream | listener table            |
| `unlisten`          | event stream | listener table            |
| `listen-ended`      | event stream | listener table            |
| `listen-error`      | event stream | listener table            |

Because `kind` is unique per table, no receiver MUST probe more than one
table to route a frame.

## 6. Request and response

6.1. A request is `{id:Q, kind:"request", type:T, payload:P}` with no
`responseOf`. The sender records Q as pending.

6.2. The receiver deserializes P, runs the handler for T, and answers
with `{id:A, kind:"response", type:T, responseOf:Q, payload:R}`. A
throwing handler is answered with
`{id:A, kind:"response-error", type:T, responseOf:Q, payload:"<detail>"}`.

6.3. A request whose `type` has no registered handler MUST be answered
with a `response-error` frame naming the unknown type, e.g.
`"unknown RPC type: <type>"`.

6.4. The sender completes the pending call on the matching answer: a
`response-error` fails the call with the payload detail; a `response`
resolves it with the payload.

6.5. Unanswered calls fail after the operation timeout (Section 11).
Calls made with no open session wait for one up to the session-wait
limit, then fail. Connection loss fails all pending calls with the
close cause.

## 7. Notifications

7.1. A notification is `{id:Q, kind:"notification", type:T, payload:P}`
with no `responseOf`. The sender does not track it.

7.2. A notification MUST never be answered, including when `type` has no
registered handler. When the type is unknown the frame is processed as a
no-op and logged. This differs from a request, which is answered when
its type is unknown.

7.3. Notifications wait for an open session up to the session-wait
limit, then are dropped with a log. Connection loss drops them
silently. No exception reaches the sender.

## 8. Byte streams

### 8.1 Concepts

A byte stream sends an application metadata object first, then raw bytes
in chunks, then a close envelope, and receives exactly one answer.
Chunk binaries carry only a stream identifier and an index; the stream
`type` lives in the envelopes, never in binary frames.

Reserved envelope kinds, never usable as application kinds:
`stream-start`, `stream-done`, `stream-abort`, `stream-reply-start`,
`stream-reply-done`.

### 8.2 Emission order

The sender MUST emit, in this order:

```
1. TEXT   {id:S, kind:"stream-start", type:T, payload:{...}}  (Section 8.3)
2. BINARY chunk 0 ... chunk N-1                               (Section 8.4)
3. TEXT   {id:D, kind:"stream-done",  type:T, payload:{...}}  (Section 8.3)
```

The sender then waits for exactly one answer (Section 8.5).

### 8.3 Envelopes

`stream-start` payload fields:

| Field         | Type        | Meaning                                              |
|---------------|-------------|------------------------------------------------------|
| `streamId`    | UUID string | fresh random identifier for this stream              |
| `metadata`    | any JSON    | application object, may be JSON null                 |
| `totalChunks` | integer     | number of binary frames to follow, always >= 1       |
| `sha256`      | HEX string  | SHA-256 over the full concatenated bytes             |

The stream handler name is carried in the frame `type`, not in the
payload.

`stream-done` payload fields: `streamId`, `sha256` (same value as in
`start`). Its `type` echoes the start `type`.

Rules: empty data still sends exactly one zero-length chunk with
`totalChunks` 1. Validation failures of `start` (unparseable, missing
fields, `totalChunks` < 1) are answered with a `response-error` and
`responseOf` set to the start frame's own `id`; no slot is reserved. A
`start` for an unregistered `type` is answered `response-error`
(`"unknown stream type: T"`); no slot is reserved. A duplicate `start`
for a live stream is answered `response-error`; the original slot is
kept.

### 8.4 Binary chunks

```
bytes  0..15 : streamId as two big-endian uint64 (most, then least)
bytes 16..19 : chunkIndex as big-endian int32, 0-based, ascending
bytes 20..   : raw payload, 0..65536 bytes
```

Frames shorter than 20 bytes are malformed: drop with a log, no reply.
Chunks for an unannounced stream are dropped with a log, no reply. A
repeated chunk index is ignored (idempotent redelivery) and MUST NOT
inflate byte accounting. A chunk index outside 0..`totalChunks`-1 is a
stream failure: discard the slot and buffers and answer with a
`response-error`. A `done` for an unknown stream is dropped with a log,
no reply.

### 8.5 Answers

Each stream is answered exactly once:

- Success: `{id:A, kind:"response", type:T, responseOf:S, payload:R}`
  where S is the `stream-start` frame `id` and T the stream `type`.
- Failure: `{id:A, kind:"response-error", type:T, responseOf:S,
  payload:"<detail>"}`.

### 8.6 Receiver completion

On a valid `done` for a live slot, the receiver MUST, in order:

1. Confirm `done.sha256` equals the slot's SHA-256
   (case-insensitive); else error `"Stream checksum header mismatch"`.
2. Require buffered chunk count equal to `totalChunks` with indexes
   covering 0..max and no gaps; else error `"Incomplete stream…"`.
3. Concatenate chunks in index order; verify SHA-256 matches;
   else error `"Checksum mismatch"`, discarding buffers.
4. Require total length within the max-bytes limit (Section 11);
   else error `"Stream exceeds max bytes…"`.
5. Run the handler for T with `(metadata, bytes)`; answer with its
   return value, or on handler failure answer `response-error` with the
   detail.

Buffers are discarded on every terminal failure. A slot whose `done`
never arrives is discarded after the slot timeout; the sender is
answered with a `response-error` if still reachable. The slot timeout is
sliding: every `start`, chunk, or `done` for the stream refreshes it,
bounded by an absolute ceiling of 300 s from slot reserve.

### 8.7 Abort

Either peer MAY abort a live stream with a fire-and-forget notification
`{id:X, kind:"stream-abort", type:T, payload:{streamId, reason}}`. The
aborter supplies `type` at the abort call site. Reply streams are not
abortable.

The receiver discards the slot and buffered chunks and settles local
waiters with the reason; no reply is ever sent for an abort, and aborts
for unknown streams are dropped with a log. The first settler wins: a
late abort or a late answer is dropped, never double-applied.

## 9. Reply-with-stream

An RPC handler MAY answer its request with a stream instead of a single
frame. The reply uses dedicated kinds:

```
1. TEXT   {id:S, kind:"stream-reply-start", type:T, responseOf:Q, payload:{...}}
2. BINARY chunk 0 ... chunk N-1
3. TEXT   {id:D, kind:"stream-reply-done",  type:T, responseOf:Q, payload:{...}}
```

`responseOf` equals the **request** `id`; `type` is the stream handler
name; chunks are keyed by a fresh `streamId` as usual. The
`stream-reply-start` payload has the same fields as `stream-start`
except there is no answer frame.

The requester treats the reply envelopes as the pending call's
completion: it reserves a slot linked to the request (no stream handler
needed), ingests chunks by `streamId`, and on valid `stream-reply-done`
resolves the request future with the assembled bytes. Integrity, cap,
and contiguity failures fail the request future locally with an error;
no separate answer frame is sent (the envelopes were the answer). A
`stream-reply-start` naming no pending request is dropped with a log.

## 10. Event streams

### 10.1 Concepts

An event stream is a persistent, peer-driven sequence of JSON events
sent to a single listener. It is modeled on Server-Sent Events: one
long-lived request whose answer is a sequence of events, not a
broker subscription. There is no backlog, no replay, and no multiple
consumer fan-out.

Event streams:

- Carry application events as complete JSON frames (no binary chunks)
- Have no predefined length; they run until explicitly ended
- Are identified by the original `listen` request's `id`
- Name their subject with the `event` field
- Support multiple concurrent listeners to the same `event` name with
  different parameters

Reserved envelope kinds for event streams:
`listen`, `listening`, `event`, `unlisten`, `listen-ended`,
`listen-error`.

### 10.2 Lifecycle

```
1. LISTENER → PUBLISHER: {id:Q, kind:"listen", event:E, payload:{data:D}}
2. PUBLISHER → LISTENER: {id:A, kind:"listening", event:E, responseOf:Q, payload:null}
3. PUBLISHER → LISTENER: {id:E1, kind:"event", event:E, responseOf:Q, payload:V1}
4. PUBLISHER → LISTENER: {id:E2, kind:"event", event:E, responseOf:Q, payload:V2}
   ... (zero or more events)
5. LISTENER → PUBLISHER: {id:U, kind:"unlisten", event:E, responseOf:Q, payload:{reason:R}}
6. PUBLISHER → LISTENER: {id:X, kind:"listen-ended", event:E, responseOf:Q, payload:null}
   OR
   PUBLISHER → LISTENER: {id:X, kind:"listen-error", event:E, responseOf:Q, payload:"<detail>"}
```

The publisher MAY also send `listen-ended` or `listen-error` at any time
after `listening`, without waiting for an `unlisten`.

### 10.3 Listen frame

A `listen` request frame:
- `id`: fresh UUID (becomes the event-stream ID)
- `kind`: `"listen"`
- `event`: application event name
- `payload`: object with optional field:
  - `data` (any JSON): parameters for this listener

The publisher MUST validate `event` and `data`. On validation failure,
answer with `listen-error`, `responseOf:Q`, and a diagnostic payload.
No listener is created.

On success, the publisher creates a listener and answers with
`listening`. The event-stream ID for all subsequent frames is `Q`.

### 10.4 Event frames

Each event is a push frame:
- `id`: fresh UUID per event
- `kind`: `"event"`
- `event`: the event name from the listen request
- `responseOf`: the event-stream ID (`Q`)
- `payload`: application event object

Events are sent at the publisher's discretion. There is no protocol-level
acknowledgment or flow control; publishers SHOULD implement application-
level backpressure or drop-on-overflow.

### 10.5 Unlisten frame

The listener MAY stop the stream at any time:
- `id`: fresh UUID
- `kind`: `"unlisten"`
- `event`: the event name
- `responseOf`: the event-stream ID (`Q`)
- `payload`: optional object with `reason` (string)

The publisher MUST reply with `listen-ended` for `Q`, stop sending
events, and clean up resources. The first party to end the stream
(listener via `unlisten`, publisher via `listen-ended`/`listen-error`,
or connection loss) wins; late frames are dropped.

### 10.6 Publisher-initiated termination

The publisher MAY end an event stream with `listen-ended` (graceful) or
`listen-error` (failure):

- `id`: fresh UUID
- `kind`: `"listen-ended"` or `"listen-error"`
- `event`: the event name
- `responseOf`: the event-stream ID (`Q`)
- `payload`: `null` for `listen-ended`; diagnostic string for
  `listen-error`

After sending either frame, the publisher MUST NOT send further events
for `Q`. The listener treats either as stream end.

A `listen-error` also serves a rejected `listen`: it may be the answer to
the original `listen` request, before any `listening`.

### 10.7 Connection loss

On connection close, all active event streams are implicitly terminated.
Publishers MUST clean up listener resources. Listeners MUST re-listen on
reconnection; there is no protocol-level resumption or event replay.

### 10.8 Multi-listener semantics

Multiple `listen` requests with the same `event` name but different
`data` are independent streams. Each receives its own event sequence.
The publisher MAY correlate them internally but MUST treat them as
separate on the wire.

### 10.9 Timeouts

Event streams are not subject to the operation timeout (Section 11).
They are long-lived by design. Idle streams SHOULD be monitored by the
application (e.g., via WebSocket ping/pong); the protocol imposes no
event-stream-specific timeout.

### 10.10 Example

```json
// Listen to usage events for server srv-123
{"id":"sub-1","kind":"listen","event":"usage","payload":{
  "data":{"serverId":"srv-123"}
}}

// Acknowledged
{"id":"ack-1","kind":"listening","event":"usage","responseOf":"sub-1","payload":null}

// Event 1
{"id":"evt-1","kind":"event","event":"usage","responseOf":"sub-1","payload":{
  "cpu":45,"mem":"2GB","timestamp":"2026-10-06T12:00:00Z"
}}

// Event 2
{"id":"evt-2","kind":"event","event":"usage","responseOf":"sub-1","payload":{
  "cpu":47,"mem":"2GB","timestamp":"2026-10-06T12:00:01Z"
}}

// Unlisten
{"id":"unsub-1","kind":"unlisten","event":"usage","responseOf":"sub-1","payload":{
  "reason":"dashboard closed"
}}

// Confirmed end
{"id":"end-1","kind":"listen-ended","event":"usage","responseOf":"sub-1","payload":null}
```

## 11. Timeouts and limits

| Constant          | Value      | Meaning                                      |
|-------------------|------------|----------------------------------------------|
| Operation timeout | 60 s       | unanswered request/stream send fails         |
| Slot timeout      | 60 s       | sliding window, refreshed per stream frame   |
| Slot ceiling      | 300 s      | absolute bound on slot life from reserve     |
| Session wait      | 300 s      | sender waits for an open session             |
| Chunk max         | 65536 B    | largest binary payload per frame             |
| Stream max        | 33554432 B | largest reassembled stream (32 MiB), enforced incrementally and finally |

## 12. Connection loss

On close, both sides MUST fail all pending sends with the close
cause and discard all receiver slots, listeners, and buffered chunks. A
fresh session starts with empty state; streams and event streams never
resume across connections — senders retry at the application level.

## 13. Interoperation checklist

- Route on `kind` alone; never on payload text.
- Validate the subject field for the kind: `type` for RPC and byte
  streams, `event` for event streams; reject a frame that carries the
  wrong one.
- Answer each request and each stream exactly as specified; never answer
  an answer, and never answer a notification.
- Acknowledge a `listen` with `listening`; confirm an `unlisten` with
  `listen-ended`.
- Preserve emission order per connection; separate concurrent streams
  by `streamId`.
- Compare digests case-insensitively; emit lowercase.
- Enforce the cap before dispatch, on every path.
- Apply the Section 11 constants on both sides.
- Treat failure payloads as opaque strings.
