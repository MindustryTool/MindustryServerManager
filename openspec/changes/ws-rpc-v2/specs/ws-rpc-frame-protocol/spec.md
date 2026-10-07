## ADDED Requirements

### Requirement: Text frame envelope with explicit kind

Every WS-RPC text frame SHALL be one JSON object containing `id` (UUID string) and `kind` (string). A payload and `responseOf` MAY be present. RPC and byte-stream frames SHALL name their application subject in `type` and SHALL NOT carry `event`. Event-stream frames SHALL name their subject in `event` and SHALL NOT carry `type`. The envelope SHALL NOT contain an `error` boolean.

#### Scenario: RPC frame carries type, not event
- **WHEN** a peer emits an RPC request or response
- **THEN** the frame has a `kind`, a `type`, and no `event` field

#### Scenario: Event-stream frame carries event, not type
- **WHEN** a peer emits an event-stream frame
- **THEN** the frame has a `kind`, an `event`, and no `type` field

#### Scenario: No error boolean exists
- **WHEN** any v2 frame is inspected
- **THEN** it contains no `error` field, and failure is expressed through the frame `kind`

### Requirement: Kind-based routing

The receiver SHALL dispatch each text frame on `kind` alone, and each known kind SHALL map to exactly one receiver table. The receiver SHALL NOT infer the frame sort from `type`, `event`, or `responseOf` presence, and SHALL NOT probe more than one table to route a frame.

#### Scenario: Response routes to the pending-call table
- **WHEN** a `response` frame arrives
- **THEN** it is matched against the pending-call table by `responseOf` only

#### Scenario: Event routes to the listener table
- **WHEN** an `event` frame arrives
- **THEN** it is matched against the listener table by `responseOf` only, never the pending-call table

#### Scenario: No cross-table probing
- **WHEN** a frame of a known kind arrives
- **THEN** exactly one receiver table is consulted for it

### Requirement: Kind-encoded failure

A failed RPC answer SHALL be a `response-error` frame whose payload is a diagnostic string. A failed or terminated event stream SHALL be a `listen-error` frame whose payload is a diagnostic string. Neither frame SHALL trigger a reply. Peers SHALL match on `kind` and `responseOf`, never on payload text.

#### Scenario: RPC handler failure
- **WHEN** a request handler throws
- **THEN** the peer transmits a `response-error` frame with `responseOf` set to the request `id` and the exception detail in the payload
- **THEN** the frame carries no `error` field

#### Scenario: Event stream failure
- **WHEN** an event-stream subscription cannot be created or is terminated with a failure
- **THEN** the peer transmits a `listen-error` frame with `responseOf` set to the listen `id`

#### Scenario: Failure frame never re-answered
- **WHEN** a `response-error` or `listen-error` frame arrives
- **THEN** it is processed and no frame is transmitted back

### Requirement: Notifications are never answered

A `notification` frame SHALL declare that no answer is expected. The receiver SHALL process it and SHALL NOT transmit any answer, including when its `type` has no registered handler.

#### Scenario: Registered notification processed
- **WHEN** a `notification` frame arrives whose `type` has a handler
- **THEN** the handler runs and no frame is transmitted back

#### Scenario: Unknown notification type is a no-op
- **WHEN** a `notification` frame arrives whose `type` has no handler
- **THEN** the frame is dropped with a log and no frame is transmitted back

### Requirement: Absent or unknown kind is dropped

A text frame whose `kind` field is absent or is not a known kind SHALL be dropped with a log and no reply. An unparsable text frame SHALL likewise be dropped with a log and no reply.

#### Scenario: Missing kind dropped
- **WHEN** a text frame without a `kind` field arrives
- **THEN** it is dropped with a log and no reply is sent

#### Scenario: Unknown kind dropped
- **WHEN** a text frame with an unrecognized `kind` arrives
- **THEN** it is dropped with a log and no reply is sent

#### Scenario: Unparsable frame dropped
- **WHEN** a text frame cannot be parsed as JSON
- **THEN** it is dropped with a log and no reply is sent

### Requirement: No reserved application names

The protocol SHALL NOT reserve any application name. A handler, stream type, or event name equal to any `kind` value SHALL be legal. Requests, notifications, and byte streams SHALL be answered with `response-error` when their `type` has no registered handler, and event streams with `listen-error` when their `event` has no registered handler.

#### Scenario: Handler may be named like a kind
- **WHEN** a handler is registered for a `type` whose value equals a frame kind such as `stream-start`
- **THEN** registration succeeds and frames with `kind="request"` and that `type` dispatch to it

#### Scenario: Unknown request type answered
- **WHEN** a `request` frame arrives whose `type` has no handler
- **THEN** the peer transmits a `response-error` naming the unknown type

#### Scenario: Unknown listen event answered
- **WHEN** a `listen` frame arrives whose `event` has no handler
- **THEN** the peer transmits a `listen-error` naming the unknown event
