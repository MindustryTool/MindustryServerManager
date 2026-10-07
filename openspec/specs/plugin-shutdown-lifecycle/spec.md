# plugin-shutdown-lifecycle Specification

## Purpose
TBD - created by archiving change plugin-shutdown-termination-flow. Update Purpose after archive.
## Requirements
### Requirement: Unload leaves the connection open

The plugin `Control.unload` SHALL tear down plugin state (`Tasks`, `Registry`, `PluginEvents`, settings) without closing the gateway connection. `ApiGateway` SHALL NOT expose or call a connection-close method during unload. The connection SHALL be ended only by process exit or by a terminal close initiated by the manager.

#### Scenario: Unload does not close the socket

- **WHEN** `UnloadServerEvent` is handled
- **THEN** plugin state is destroyed and the gateway session remains open

#### Scenario: No connection is built during unload

- **WHEN** unload runs after `Registry.destroy()`
- **THEN** no new `ApiGateway` is created and no connect is attempted

### Requirement: Terminal close suppresses reconnect

When the manager closes the plugin session with code `4234`, the plugin `WsClient` SHALL transition to `KICKED` and SHALL NOT schedule a reconnect.

#### Scenario: Manager termination close does not redial

- **WHEN** the plugin receives a remote close with code `4234`
- **THEN** the client state becomes `KICKED` and no reconnect is scheduled

### Requirement: Exit disposition is explicit

Self-update SHALL end the process with a non-zero exit so the container restart policy restarts it. Manager-initiated termination SHALL NOT self-exit; the manager force-removes the container instead. The container restart policy SHALL NOT change.

#### Scenario: Self-update restarts

- **WHEN** a pending bundle update is applied
- **THEN** the plugin unloads and exits non-zero, and the container restarts

#### Scenario: Termination does not self-exit

- **WHEN** the manager sends the `shutdown` request during termination
- **THEN** the plugin unloads and remains running until the manager removes the container

### Requirement: Graceful unload before terminal close

The `shutdown` handler SHALL complete the unload and return a response before the manager closes the session, allowing the manager to observe the unload within its shutdown timeout.

#### Scenario: Shutdown request is answered

- **WHEN** the manager sends the `shutdown` request
- **THEN** the plugin unloads and returns before the manager closes the session

#### Scenario: Slow unload does not block termination forever

- **WHEN** the unload does not return within the manager's timeout
- **THEN** the manager proceeds to close the session and remove the container

