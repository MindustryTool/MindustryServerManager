# backend-rpc-gateway

## Purpose

Bidirectional RPC command dispatch and event streaming between Backend API and Server Manager using the `WsMessage` envelope. Synced from change wss-backend-communication.

## Requirements

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

### Requirement: Removal of Public Inbound HTTP Routes
The Server Manager SHALL NOT expose the `/api/v2/*` HTTP endpoints on Javalin port 8088, and SHALL retain only the `/gateway` WebSocket endpoint on port 8088 for local Docker container plugin connections.

#### Scenario: Blocked inbound HTTP access
- **WHEN** an external HTTP client requests `/api/v2/servers` or `/api/v2/events` on port 8088
- **THEN** Javalin returns HTTP 404 Not Found

#### Scenario: Local plugin gateway remains operational
- **WHEN** a local Mindustry game container connects to `ws://server-manager:8088/gateway`
- **THEN** Javalin establishes the WebSocket connection and routes it to `WsHandler`
