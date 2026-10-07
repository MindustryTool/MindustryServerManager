## MODIFIED Requirements

### Requirement: Bidirectional RPC Command Dispatch
The Server Manager SHALL accept JSON `WsMessage` command frames (`kind="request"`) received from the Backend API, route them to the appropriate local manager service, and transmit a corresponding `WsMessage` response frame (`kind="response"` or `kind="response-error"`) containing the same correlation ID in `responseOf`.

#### Scenario: Server host command execution
- **WHEN** Backend sends a `host-server` request with server configuration
- **THEN** Server Manager triggers `ServerService.host()` and returns a `response` frame with `responseOf` set to the request ID

#### Scenario: Command execution failure
- **WHEN** an error or exception occurs while executing an RPC command
- **THEN** Server Manager returns a `response-error` frame with the error message in the payload

### Requirement: Event Bus Forwarding to Backend
The Server Manager SHALL forward all `BaseEvent` events emitted onto its internal `EventBus` (including player join, player leave, server crash, and server start events) as unsolicited `WsMessage` notification frames (`kind="notification"`) to the Backend API, and SHALL NOT expect an answer for them.

#### Scenario: Event streaming
- **WHEN** a game server container emits an event onto the `EventBus`
- **THEN** Server Manager serializes the event into a `notification` frame and transmits it over the WSS connection to the Backend API

#### Scenario: Notification is never answered
- **WHEN** the Backend API receives an event `notification` frame
- **THEN** it processes the frame and transmits no reply, even if the event type is unknown
