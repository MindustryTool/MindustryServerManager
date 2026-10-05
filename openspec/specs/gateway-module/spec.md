# gateway-module

## Purpose

TBD - created by syncing change extract-gateway-subproject. Update Purpose after implementation.

## Requirements

### Requirement: Standalone Gateway Module Setup
The system SHALL provide a dedicated Gradle subproject `:gateway` that compiles with Java 17 and packages `gateway.WsMessage`, `gateway.session.WsSession`, and `gateway.rpc.WsRpcChannel` without depending on Mindustry or Arc libraries.

#### Scenario: Subproject compilation
- **WHEN** `./gradlew :gateway:build` is executed
- **THEN** `:gateway` compiles successfully with only standard Java and Jackson dependencies

### Requirement: Transport Abstraction WsSession
The `:gateway` module SHALL define a transport interface `WsSession` providing methods to send text messages, send binary buffers, close the session with status code and reason, and query whether the connection is currently open.

#### Scenario: Adapter invokes sendText
- **WHEN** an RPC channel sends a formatted JSON payload through a `WsSession`
- **THEN** `WsSession.sendText()` delegates to the underlying transport implementation

### Requirement: Request-Response RPC Channel Multiplexing
The `:gateway` module SHALL provide `WsRpcChannel` capable of matching asynchronous request `id` values with subsequent response `responseOf` values using `CompletableFuture`, dispatching incoming requests to registered typed message handlers, and completing pending requests exceptionally when timeouts expire or connections close.

#### Scenario: Asynchronous request-response correlation
- **WHEN** `WsRpcChannel.sendRequest()` is invoked with a message type and payload
- **THEN** it generates a unique message `id`, stores a pending future, transmits the `WsMessage`, and completes the future when a matching `responseOf` message arrives

#### Scenario: Request timeout handling
- **WHEN** a remote peer does not respond within the configured timeout duration
- **THEN** the pending future is completed exceptionally with a `TimeoutException` and removed from memory

#### Scenario: Incoming message handler execution
- **WHEN** a `WsMessage` arrives matching a registered message handler type
- **THEN** `WsRpcChannel` deserializes the payload, executes the handler, and transmits a response `WsMessage` containing the matching `responseOf` ID
