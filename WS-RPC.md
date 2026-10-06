# WS RPC Protocol (WS-RPC)

Status: Draft — Version 1
Scope: Complete wire protocol between two symmetric peers.
This document is implementation-neutral and application-neutral.
It contains no source code.
JSON examples are wire illustrations, not program code.

## 1. Introduction

WS-RPC multiplexes three traffic kinds over one WebSocket connection:

1. Request/response remote calls with typed correlation.
2. One-way notifications.
3. Metadata-first byte streams with integrity verification.

Either peer may send requests, notifications, or streams. Either peer
may answer them. The protocol does not define client or server roles.

Design goals: single connection, explicit correlation, fail-loud
errors, bounded memory, ordered delivery per sender.

## 2. Conventions

The key words MUST, MUST NOT, SHALL, SHALL NOT, SHOULD, MAY follow
RFC 2119.

- UUID: canonical lowercase string form,
  e.g. `123e4567-e89b-12d3-a456-426614174000`.
- HEX: lowercase hexadecimal, e.g. SHA-256 digests.
- INTEGERS: unsigned, big-endian (network byte order) on the wire.
- JSON: UTF-8 encoded text frames. Field order is insignificant.
- Error detail strings are diagnostic only. Peers MUST match on the
  `error` flag and `responseOf` fields, never on message text.

## 3. Transport

3.1. Peers communicate over one WebSocket connection carrying two
frame kinds: UTF-8 JSON text frames (control) and binary frames
(stream chunks).

3.2. Order: frames MUST be transmitted and processed in call order
per connection. A stream `start` frame MUST never be reordered after
its own chunks. Concurrent streams from one sender interleave freely;
the receiver separates them by stream identifier (Section 7).

3.3. Keepalive: peers MAY use WebSocket ping/pong. Recommended
practice is a ping every 20 seconds, declare the peer dead after
45 seconds without a pong, and reconnect with capped exponential
backoff. Keepalive policy never changes framing.

## 4. Text envelope

Every control frame is one JSON object with these fields:

| Field      | Type            | Required | Meaning                                   |
|------------|-----------------|----------|-------------------------------------------|
| `id`       | UUID string     | yes      | unique per frame; correlation anchor      |
| `type`     | string          | yes*     | frame type (*absent type is dropped)      |
| `payload`  | any JSON        | no       | body; error frames carry a JSON string    |
| `responseOf`| UUID string    | no       | when present, answers the frame with that `id` |
| `error`    | boolean         | no       | default false; true marks a failure answer|

4.1. A frame carrying `responseOf` is an answer. Answers MUST NOT
trigger further answers, even when malformed.

4.2. A frame without a `type` is dropped with a log. No reply.

4.3. An unparsable text frame is dropped with a log. No reply.

4.4. A non-answer frame whose `type` has no registered handler
MUST be answered with an error frame: `error` true, `responseOf`
set to the incoming `id`, payload naming the unknown type,
e.g. `"unknown RPC type: <type>"`. This applies to requests and
to unsolicited notifications alike.

## 5. Request and response

5.1. A request is a text frame `{id:Q, type:T, payload:P}` with no
`responseOf`. The sender records Q as pending.

5.2. The receiver deserializes P, runs the handler for T, and answers
with `{id:A, type:T, responseOf:Q, payload:R}`. A throwing handler
is answered with `{error:true, responseOf:Q, payload:"<detail>"}`.

5.3. The sender completes the pending call on the matching answer:
an `error:true` answer fails the call with the payload detail;
any other answer resolves it with the payload.

5.4. Unanswered calls fail after the operation timeout (Section 10).
Calls made with no open session wait for one up to the session-wait
limit, then fail. Connection loss fails all pending calls with the
close cause.

## 6. Notifications

6.1. A notification is a text frame like a request whose answer is
never consumed. Senders do not track it.

6.2. Notifications wait for an open session up to the session-wait
limit, then are dropped with a log. Connection loss drops them
silently. No exception reaches the sender.

## 7. Streams

### 7.1 Concepts

A stream sends an application metadata object first, then raw bytes
in chunks, then a close envelope, and receives exactly one answer.
Chunk binaries carry only a stream identifier and an index; the
stream `type` lives in the envelopes, never in binary frames.

Reserved envelope types, never usable as application types:
`stream-start`, `stream-done`, `stream-abort`.

### 7.2 Emission order

The sender MUST emit, in this order:

```
1. TEXT   {id:S, type:"stream-start", payload:{...}}   (Section 7.3)
2. BINARY chunk 0 ... chunk N-1                        (Section 7.4)
3. TEXT   {id:D, type:"stream-done", payload:{...}}    (Section 7.3)
```

The sender then waits for exactly one answer (Section 7.5).

### 7.3 Envelopes

`stream-start` payload fields:

| Field         | Type        | Meaning                                              |
|---------------|-------------|------------------------------------------------------|
| `streamId`    | UUID string | fresh random identifier for this stream              |
| `streamType`  | string      | application type, never a reserved type              |
| `metadata`    | any JSON    | application object, may be JSON null                 |
| `totalChunks` | integer     | number of binary frames to follow, always >= 1       |
| `sha256`      | HEX string  | SHA-256 over the full concatenated bytes             |

`stream-done` payload fields: `streamId`, `sha256` (same value as
in `start`).

Rules: empty data still sends exactly one zero-length chunk with
`totalChunks` 1. Validation failures of `start` (unparseable,
missing fields, `totalChunks` < 1) are answered with `error:true`
and `responseOf` set to the start frame's own `id`; no slot is
reserved. A `start` for an unregistered `streamType` is answered
`error:true` (`"unknown stream type: T"`); no slot is reserved.
A duplicate `start` for a live stream is answered `error:true`;
the original slot is kept.

### 7.4 Binary chunks

```
bytes  0..15 : streamId as two big-endian uint64 (most, then least)
bytes 16..19 : chunkIndex as big-endian int32, 0-based, ascending
bytes 20..   : raw payload, 0..65536 bytes
```

Frames shorter than 20 bytes are malformed: drop with a log, no
reply. Chunks for an unannounced stream are dropped with a log, no
reply. A repeated chunk index is ignored (idempotent redelivery)
and MUST NOT inflate byte accounting. A chunk index outside
0..`totalChunks`-1 is a stream failure: discard the slot and
buffers and answer with an error. A `done` for an unknown
stream is dropped with a log, no reply.

### 7.5 Answers

Each stream is answered exactly once:

- Success: `{id:A, type:T, responseOf:S, payload:R, error:false}`
  where S is the `stream-start` frame `id` and T the stream type.
- Failure: `{id:A, type:T, responseOf:S, payload:"<detail>",
  error:true}`.

### 7.6 Receiver completion

On a valid `done` for a live slot, the receiver MUST, in order:

1. Confirm `done.sha256` equals the slot's SHA-256
   (case-insensitive); else error `"Stream checksum header mismatch"`.
2. Require buffered chunk count equal to `totalChunks` with indexes
   covering 0..max and no gaps; else error `"Incomplete stream…"`.
3. Concatenate chunks in index order; verify SHA-256 matches;
   else error `"Checksum mismatch"`, discarding buffers.
4. Require total length within the max-bytes limit (Section 10);
   else error `"Stream exceeds max bytes…"`.
5. Run the handler for T with `(metadata, bytes)`; answer with its
   return value, or on handler failure answer `error:true` with the
   detail.

Buffers are discarded on every terminal failure. A slot whose `done`
never arrives is discarded after the slot timeout; the sender is
answered with an error if still reachable. The slot timeout is
sliding: every `start`, chunk, or `done` for the stream refreshes
it, bounded by an absolute ceiling of 300 s from slot reserve.

### 7.7 Abort

Either peer MAY abort a live stream with a fire-and-forget
notification `{id:X, type:"stream-abort",
payload:{streamId, reason}}`. The receiver discards the slot and
buffered chunks and settles local waiters with the reason; no
reply is ever sent for an abort, and aborts for unknown streams
are dropped with a log. The first settler wins: a late abort or
a late answer is dropped, never double-applied.

## 8. Reply-with-stream

An RPC handler MAY answer its request with a stream instead of a
single frame. The reply's `stream-start` and `stream-done` carry
`responseOf` equal to the **request** `id`; chunks are keyed by a
fresh `streamId` as usual.

The requester treats such envelopes as the pending call's
completion: it reserves a slot linked to the request (no stream
handler needed), ingests chunks by `streamId`, and on valid `done`
resolves the request future with the assembled bytes. Integrity,
cap, and contiguity failures fail the request future locally with
an error; no separate answer frame is sent (the envelopes were the
answer). A `start` naming no pending request is dropped with a log.

## 9. Application use

9.1. Stream types, metadata schemas, and result schemas are defined
by the application, not by this protocol. The protocol carries them
opaquely: `streamType` names the handler, `metadata` carries the
sender's object, and the answer carries the handler's return value.

9.2. Any request MAY be answered with a reply-with-stream (Section 8)
so the request resolves with bytes — useful whenever a response is
too large or too binary for a single frame.

9.3. Abstract illustration with application type T:

```json
// request
{"id":"Q","type":"get-blob","payload":{"key":"k"},...}
// reply: stream-start
{"id":"S","type":"stream-start","responseOf":"Q","payload":{
  "streamId":"G","streamType":"get-blob",
  "metadata":{"key":"k","size":3},
  "totalChunks":1,
  "sha256":"…"},...}
// binary: 20-byte header(G,0) + 3 payload bytes
// reply: stream-done
{"id":"D","type":"stream-done","responseOf":"Q",
 "payload":{"streamId":"G","sha256":"…"},...}
// request Q now resolves with the 3 bytes; no further answer follows
```

## 10. Timeouts and limits

| Constant        | Value      | Meaning                                      |
|-----------------|------------|----------------------------------------------|
| Operation timeout | 60 s     | unanswered request/stream send fails         |
| Slot timeout      | 60 s     | sliding window, refreshed per stream frame   |
| Slot ceiling      | 300 s    | absolute bound on slot life from reserve     |
| Session wait      | 300 s    | sender waits for an open session             |
| Chunk max         | 65536 B    | largest binary payload per frame             |
| Stream max        | 33554432 B | largest reassembled stream (32 MiB), enforced incrementally and finally |

## 11. Connection loss

On close, both sides MUST fail all pending sends with the close
cause and discard all receiver slots and buffered chunks. A fresh
session starts with empty state; streams never resume across
connections — senders retry at the application level.

## 12. Interoperation checklist

- Route on (`type`, `responseOf`, `error`); never on message text.
- Answer each request, notification-unknown-type, and stream
  exactly as specified; never answer an answer.
- Preserve emission order per connection; separate concurrent
  streams by `streamId`.
- Compare digests case-insensitively; emit lowercase.
- Enforce the cap before dispatch, on every path.
- Apply the Section 10 constants on both sides.
- Treat error payloads as opaque strings.
