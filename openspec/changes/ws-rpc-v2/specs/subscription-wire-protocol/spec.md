## REMOVED Requirements

### Requirement: Subscribe frame wire format
**Reason**: Replaced by the SSE-like `listen` frame carrying the event name in the `event` field and parameters under `payload.data`.
**Migration**: Emit `{id, kind:"listen", event, payload:{data}}` instead of `{id, type:"subscribe", payload:{eventType, data}}`.

### Requirement: Event frame wire format
**Reason**: Event frames no longer reuse answer fields (`type`, `error`); they are `kind:"event"` frames keyed by `event` and `responseOf`.
**Migration**: Emit `{id, kind:"event", event, responseOf, payload}`.

### Requirement: Unsubscribe frame wire format
**Reason**: Replaced by `unlisten`, which the publisher now confirms with `listen-ended`.
**Migration**: Emit `{id, kind:"unlisten", event, responseOf, payload:{reason}}` and await `listen-ended`.

### Requirement: Server-initiated termination wire format
**Reason**: Termination is expressed by dedicated kinds instead of an `error` boolean.
**Migration**: Use `kind:"listen-ended"` for graceful end and `kind:"listen-error"` for failure, each with `event` and `responseOf`.

### Requirement: Reserved types cannot be registered as handlers
**Reason**: v2 reserves no application names; frame sort is carried by `kind`.
**Migration**: Remove the reserved-type rejection; register any handler or event name, including names equal to kind values.

### Requirement: Subscription ID equals subscribe request ID
**Reason**: Superseded by the event-stream correlator requirement below.
**Migration**: Continue to use the `listen` request `id` as the `responseOf` of all subsequent frames.

### Requirement: No reply for unsubscribe frame
**Reason**: `unlisten` is now confirmed with `listen-ended` for deterministic teardown.
**Migration**: Reply to `unlisten` with a `listen-ended` frame carrying the same `responseOf`.

## ADDED Requirements

### Requirement: Listen frame wire format
The system SHALL transmit a `listen` frame to start an event stream:
- `id`: fresh UUID (becomes the event-stream ID)
- `kind`: `"listen"`
- `event`: application event name
- `payload`: object with optional `data` (any JSON) parameters

#### Scenario: Valid listen frame sent
- **WHEN** client calls `listen("usage", {"serverId":"srv-123"}, handler)`
- **THEN** a frame is sent with `kind="listen"`, `event="usage"`, and `payload={"data":{"serverId":"srv-123"}}`

#### Scenario: Listen with no data
- **WHEN** client calls `listen("events", null, handler)`
- **THEN** a frame is sent with `event="events"` and no `data` field

### Requirement: Listening acknowledgement frame
The publisher SHALL answer a valid `listen` with a `listening` frame:
- `id`: fresh UUID
- `kind`: `"listening"`
- `event`: the event name
- `responseOf`: the `listen` request `id`
- `payload`: JSON null

#### Scenario: Listen acknowledged
- **WHEN** the publisher creates a listener
- **THEN** it sends a `listening` frame with `responseOf` equal to the `listen` `id` before any `event`

### Requirement: Event push frame wire format
The publisher SHALL push each event as an `event` frame:
- `id`: fresh UUID per event
- `kind`: `"event"`
- `event`: the event name from the `listen` request
- `responseOf`: the event-stream ID
- `payload`: application event object

#### Scenario: Event correlates to event stream
- **WHEN** the publisher pushes an event for event-stream `sub-123`
- **THEN** the frame has `kind="event"`, `responseOf="sub-123"`, and `event="usage"`

#### Scenario: Each event has unique ID
- **WHEN** two events are pushed for the same event stream
- **THEN** each has a different `id`

### Requirement: Unlisten frame wire format
The listener SHALL transmit an `unlisten` frame to stop an event stream:
- `id`: fresh UUID
- `kind`: `"unlisten"`
- `event`: the event name
- `responseOf`: the event-stream ID
- `payload`: optional object with `reason` (string)

#### Scenario: Unlisten frame sent
- **WHEN** client calls `unlisten("sub-123", "dashboard closed")`
- **THEN** the frame has `kind="unlisten"`, `responseOf="sub-123"`, `payload={"reason":"dashboard closed"}`

#### Scenario: Unlisten without reason
- **WHEN** client calls `unlisten("sub-123")`
- **THEN** the frame has `payload={}` or omitted

### Requirement: Listen-ended confirmation frame
The publisher SHALL answer an `unlisten` with a `listen-ended` frame, and MAY send `listen-ended` at any time after `listening` to end the stream gracefully:
- `id`: fresh UUID
- `kind`: `"listen-ended"`
- `event`: the event name
- `responseOf`: the event-stream ID
- `payload`: JSON null

#### Scenario: Unlisten confirmed
- **WHEN** the publisher receives `unlisten` for an active stream
- **THEN** it sends a `listen-ended` frame with the same `responseOf` and sends no further `event`

#### Scenario: Publisher ends gracefully
- **WHEN** the publisher decides to end an active event stream
- **THEN** it sends `listen-ended` and sends no further `event`

### Requirement: Listen-error termination frame
The publisher SHALL signal a rejected `listen` or a failed active stream with a `listen-error` frame:
- `id`: fresh UUID
- `kind`: `"listen-error"`
- `event`: the event name
- `responseOf`: the event-stream ID
- `payload`: diagnostic string

#### Scenario: Rejected listen
- **WHEN** the publisher cannot create the listener
- **THEN** it answers the `listen` with `listen-error` and creates no listener

#### Scenario: Active stream fails
- **WHEN** an active event stream fails after `listening`
- **THEN** the publisher sends `listen-error` and sends no further `event`

### Requirement: Event-stream ID equals listen request ID
The system SHALL use the original `listen` request's `id` as the `responseOf` value for all subsequent frames (`listening`, `event`, `unlisten`, `listen-ended`, `listen-error`).

#### Scenario: All frames correlate to listen ID
- **WHEN** an event stream is created with request ID `sub-123`
- **THEN** `listening`, `event`, `unlisten`, and termination frames all have `responseOf="sub-123"`

### Requirement: Any event name is legal
The system SHALL NOT reserve any event name. An event name equal to a frame kind SHALL be accepted.

#### Scenario: Event named like a kind
- **WHEN** a `listen` frame arrives with `event="event"` or `event="listen"`
- **THEN** it dispatches to the handler registered for that event name

### Requirement: Connection close terminates event streams implicitly
The system SHALL treat connection close as implicit event-stream termination. Publishers SHALL clean up listener resources and listeners SHALL fail pending `listen` futures and remove handlers.

#### Scenario: Server cleans up on listener disconnect
- **WHEN** the WebSocket connection closes
- **THEN** the publisher cleans up all listener resources for that connection

#### Scenario: Client cleans up on publisher disconnect
- **WHEN** the WebSocket connection closes
- **THEN** the listener fails pending `listen` futures and removes handlers
