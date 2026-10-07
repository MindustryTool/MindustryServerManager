## ADDED Requirements

### Requirement: Adopt session before granting reads

`JdkWsClient` SHALL NOT enable inbound delivery until the channel has adopted the transport as its session. `Transport.attach` SHALL bind the socket and start the sender and ping without requesting frames; a single `Transport.beginRead()` SHALL grant the first read; `JdkWsClient` SHALL call `WsRpcChannel.onOpen(transport)` before `beginRead()`. The `WebSocket.Listener.onOpen` override SHALL remain and SHALL NOT request a frame, because the JDK default `onOpen` requests one automatically.

#### Scenario: No frame delivered before adoption

- **WHEN** the server sends a frame immediately after the handshake completes and `JdkWsClient` has not yet called `rpcChannel.onOpen`
- **THEN** the frame is not delivered, because no read has been requested yet

#### Scenario: Reply flows once adopted

- **WHEN** the channel adopts the transport and only then `beginRead()` is called
- **THEN** inbound frames are delivered and a request answered in the same window resolves normally instead of logging `no open session`

#### Scenario: Listener onOpen does not self-request

- **WHEN** the JDK invokes `WebSocket.Listener.onOpen`
- **THEN** no `request(n)` is issued by the listener, so the default JDK auto-request cannot open a pre-adoption window

#### Scenario: Attach without read

- **WHEN** a fresh transport is attached but the connect is superseded before `beginRead()`
- **THEN** the transport never requests frames and terminates without delivering inbound to the channel
