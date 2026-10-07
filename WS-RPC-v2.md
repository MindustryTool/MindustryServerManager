# WS-RPC v2 — Change Supplement

Status: Draft — Version 2
Scope: Delta between WS-RPC v1 (`WS-RPC.md` before rewrite) and WS-RPC v2.
This document is a changelog and rationale. The normative text is the
rewritten `WS-RPC.md`, which describes v2 in full.

## 1. Motivation

v1 overloaded two envelope fields:

- `type` meant both a **frame kind** (`subscribe`, `stream-start`) and an
  **application name** (handler, stream type, event topic).
- `responseOf` meant an RPC answer, a reply-with-stream envelope, and a
  subscription event, distinguished only by probing two maps in a fixed
  order.

The `type` overload forced a reserved-word list (`subscribe`,
`unsubscribe`, `stream-start`, `stream-done`, `stream-abort`) that
permanently poisoned the application namespace. The `responseOf` overload
made routing an ordered map probe.

## 2. Summary of changes

| Area | v1 | v2 |
|------|----|----|
| Frame discriminator | implicit in `type` | explicit `kind` field, required |
| Reserved type names | list, rejected at registration | none |
| `type` meaning | overloaded | application subject for RPC and byte streams |
| Event streams | reused `type`/`responseOf` | own family with `event` field (SSE-like) |
| `responseOf` meaning | overloaded (answer / reply-stream / event) | fixed per `kind`; one kind maps to one receiver table |
| Failure marker | `error` boolean | encoded in `kind` (`response-error`, `listen-error`) |
| Subscribe ack | first/empty event | dedicated `listening` frame |
| Unsubscribe ack | none (fire-and-forget) | publisher replies `listen-ended` |
| Notification unknown type | answered with an error | never answered; drop with a log |
| Version negotiation | none | none (v1 not deployed) |
| Subscription flow control | none | none (deferred) |
| Error codes | none (detail string only) | none (detail string only) |

## 3. New envelope

```
v1 fields: id, type, payload, responseOf, error
v2 fields: id, kind, type, event, payload, responseOf
```

- `kind` (required): the frame kind. A frame with an absent or unknown
  `kind` is dropped with a log and no reply.
- `type`: application subject for RPC and byte-stream frames. Absent on
  event-stream frames.
- `event`: event name for event-stream frames. Absent on other frames.
- `error` boolean removed. Failure is expressed by a dedicated kind.

## 4. Kind vocabulary

```
RPC              request, response, response-error, notification
Byte streams     stream-start, stream-done, stream-abort,
                 stream-reply-start, stream-reply-done
Event streams    listen, listening, event, unlisten,
                 listen-ended, listen-error
```

Routing is a `switch (kind)`. Each kind maps to exactly one receiver
table; no field-presence inference, no cross-map probing.

## 5. Application subject field

`type` was the universal name in v1. In v2 each family names its subject
honestly:

```
RPC / byte streams : type  (handler / stream handler name)
Event streams      : event (event name)
```

This asymmetry is deliberate: event streams follow the SSE `event:`
field, and do not pretend to be a topic-based broker.

## 6. Event stream family

Renamed and reshaped from v1's "subscription streams".

| v1 | v2 |
|----|----|
| `subscribe` (`eventType` in payload) | `listen` (`event` field is the name) |
| first/empty event as ack | `listening` |
| `unsubscribe` (fire-and-forget) | `unlisten`, answered with `listen-ended` |
| publisher error / end | `listen-ended` (graceful) and `listen-error` (failure) |

`listen` payload carries subscription parameters under `data`.
`unlisten` carries an optional `{reason}`.

## 7. Byte stream changes

- `stream-start`, `stream-done`, `stream-abort` now echo `type`
  (the stream handler name).
- Reply-with-stream uses dedicated kinds `stream-reply-start` and
  `stream-reply-done` instead of a `stream-start`/`stream-done` pair
  distinguished by `responseOf` presence.
- Abort remains a single `stream-abort` kind. Reply streams are not
  abortable.
- The aborter supplies `type` at the abort call site.

## 8. Failure model

```
RPC answer failure      : kind response-error, payload = detail string
Event stream failure    : kind listen-error,   payload = detail string
```

The `error` boolean is gone. Receivers match on `kind`; text is
diagnostic only, as in v1.

## 9. Behavior changes

- Notifications are a distinct kind and are **never** answered, even for
  an unregistered `type`. v1 answered an unknown-type notification with
  an error.
- `listen` is explicitly acknowledged with `listening`, so a silent
  publisher is distinguishable from a rejected one.
- `unlisten` is confirmed with `listen-ended`, giving deterministic
  teardown instead of relying on silence.

## 10. Deferred / out of scope

- Version negotiation: omitted because v1 is not deployed anywhere.
- Error codes: omitted; detail strings only, matched never.
- Subscription flow control / backpressure: deferred entirely.
- Stream/subscription resumption across connections: still unsupported.

## 11. Migration

v2 is a breaking wire change. Both peers must speak v2. Because v1 was
never deployed, no dual-stack or negotiation shim is required.
