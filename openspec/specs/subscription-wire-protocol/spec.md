# subscription-wire-protocol

## Purpose

Wire format for subscription streams: subscribe, event, unsubscribe, and termination frames, plus reserved types and correlation rules. Synced from change add-subscription-streams.

## Requirements

### Requirement: Subscribe frame wire format
The system SHALL transmit `subscribe` frames with the following structure:
- `id`: fresh UUID (becomes the subscription ID)
- `type`: `"subscribe"`
- `payload`: object with `eventType` (string, required) and `data` (any JSON, optional)

#### Scenario: Valid subscribe frame sent
- **WHEN** client calls `subscribe("usage", {"serverId":"srv-123"}, handler)`
- **THEN** a frame is sent with `type="subscribe"` and `payload={"eventType":"usage","data":{"serverId":"srv-123"}}`

#### Scenario: Subscribe with no data payload
- **WHEN** client calls `subscribe("events", null, handler)`
- **THEN** a frame is sent with `payload={"eventType":"events"}` (no `data` field)

### Requirement: Event frame wire format
The system SHALL transmit event frames as standard answer frames:
- `id`: fresh UUID per event
- `type`: the `eventType` from the subscribe request
- `responseOf`: the subscription ID (original `subscribe` frame `id`)
- `payload`: application event object
- `error`: `false` (omitted or explicit)

#### Scenario: Event frame correlates to subscription
- **WHEN** server pushes event for subscription `sub-123`
- **THEN** frame has `responseOf="sub-123"` and `type="usage"`

#### Scenario: Each event has unique ID
- **WHEN** two events are pushed for the same subscription
- **THEN** each has a different `id`

### Requirement: Unsubscribe frame wire format
The system SHALL transmit `unsubscribe` frames:
- `id`: fresh UUID
- `type`: `"unsubscribe"`
- `responseOf`: the subscription ID
- `payload`: optional object with `reason` (string)

#### Scenario: Unsubscribe frame sent
- **WHEN** client calls `unsubscribe("sub-123", "dashboard closed")`
- **THEN** frame has `type="unsubscribe"`, `responseOf="sub-123"`, `payload={"reason":"dashboard closed"}`

#### Scenario: Unsubscribe without reason
- **WHEN** client calls `unsubscribe("sub-123")`
- **THEN** frame has `payload={}` or omitted

### Requirement: Server-initiated termination wire format
The system SHALL transmit error frames to end subscriptions:
- `id`: fresh UUID
- `type`: the subscription's `eventType`
- `responseOf`: the subscription ID
- `error`: `true`
- `payload`: diagnostic string

#### Scenario: Server fail sends error frame
- **WHEN** server calls `handle.fail("server not found")`
- **THEN** frame has `type="usage"`, `responseOf="sub-123"`, `error=true`, `payload="server not found"`

### Requirement: Reserved types cannot be registered as handlers
The system SHALL reject handler registration for types `"subscribe"` and `"unsubscribe"` with `IllegalArgumentException`.

#### Scenario: Registering subscribe handler throws
- **WHEN** server calls `registerHandler("subscribe", ...)` or `registerSubscriptionHandler("subscribe", ...)`
- **THEN** `IllegalArgumentException` is thrown

#### Scenario: Registering unsubscribe handler throws
- **WHEN** server calls `registerHandler("unsubscribe", ...)` or `registerSubscriptionHandler("unsubscribe", ...)`
- **THEN** `IllegalArgumentException` is thrown

### Requirement: Subscription ID equals subscribe request ID
The system SHALL use the original `subscribe` request's `id` as the `responseOf` value for all subsequent frames (events, error, unsubscribe ack).

#### Scenario: All frames correlate to subscribe ID
- **WHEN** subscription created with request ID `sub-123`
- **THEN** event frames have `responseOf="sub-123"`
- **THEN** error frame has `responseOf="sub-123"`
- **THEN** unsubscribe frame has `responseOf="sub-123"`

### Requirement: No reply for unsubscribe frame
The system SHALL NOT send a reply frame for `unsubscribe` notifications (fire-and-forget per WS-RPC 13.5).

#### Scenario: Unsubscribe received by server
- **WHEN** server receives `unsubscribe` frame
- **THEN** server cleans up subscription
- **THEN** server does NOT send any frame in response

### Requirement: Connection close terminates subscriptions implicitly
The system SHALL treat connection close as implicit subscription termination per WS-RPC 13.7.

#### Scenario: Server cleans up on client disconnect
- **WHEN** WebSocket connection closes
- **THEN** server cleans up all subscription resources for that connection

#### Scenario: Client cleans up on server disconnect
- **WHEN** WebSocket connection closes
- **THEN** client fails pending subscribe futures and removes handlers
