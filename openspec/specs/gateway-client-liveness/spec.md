# gateway-client-liveness Specification

## Purpose

Tracks per-server gateway client connection liveness for the manager: socket-closed definition, disconnect-age clock, and cleanup of orphaned clients.
## Requirements
### Requirement: Socket-closed definition

The system SHALL treat a gateway client as not connected when `rpcChannel.getSession()` is `null` or the session reports not open. A stale `onClose` for a non-current session SHALL NOT change the connected state, and an `onOpen` overwrite SHALL immediately adopt the new session.

#### Scenario: Null session counts as closed

- **WHEN** no session has been opened or the channel has been closed
- **THEN** the client counts as not connected

#### Scenario: Closed session counts as closed

- **WHEN** a session exists but `isOpen()` returns false
- **THEN** the client counts as not connected

#### Scenario: Open session counts as connected

- **WHEN** a session exists and `isOpen()` returns true
- **THEN** the client counts as connected

#### Scenario: Stale close keeps live session connected

- **WHEN** `onClose` arrives for a session that is not current while a newer session is open
- **THEN** the client still counts as connected and no disconnect clock starts

### Requirement: Reusable client handle

`GatewayService.of(serverId)` SHALL return a cached, reusable per-server handle. The handle SHALL NOT carry lifecycle state that gates behavior: there SHALL be no `removed` flag and no `terminatedAt` field, `onOpen` SHALL always adopt the incoming session, and `onMessage`/`onBinary` SHALL NOT be rejected based on prior termination.

#### Scenario: Open always adopts

- **WHEN** `onOpen` runs for a cached handle, including after a prior termination
- **THEN** the new session is adopted and the client is usable

#### Scenario: No removed guard

- **WHEN** a message or binary frame arrives for a cached handle
- **THEN** it is dispatched without a removed-state check

#### Scenario: Cache miss builds a handle

- **WHEN** `of(serverId)` is called for a server with no cached handle
- **THEN** a new reusable handle is created and cached

### Requirement: Batched eviction sweep

The scheduler SHALL evict a cached `GatewayClient` when its socket is closed and its container is not running, using a single batched container list per sweep. The sweep SHALL be memory hygiene only and SHALL NOT remove containers, SHALL NOT terminate clients, and SHALL NOT represent a lifecycle transition.

#### Scenario: Socket closed and container gone evicts

- **WHEN** a cached handle has a closed socket and its container is absent from the batched running set
- **THEN** it is removed from the client map

#### Scenario: Running container keeps the handle

- **WHEN** a cached handle's container is present in the batched running set
- **THEN** it is not evicted

#### Scenario: One container list per sweep

- **WHEN** a sweep runs
- **THEN** at most one container-listing call is made for all cached handles

#### Scenario: Sweep never removes containers

- **WHEN** a cached handle's socket is closed while its container still runs
- **THEN** the sweep leaves the container untouched and removes no container

### Requirement: Termination close and signals

`GatewayService.terminate` SHALL send the `shutdown` request and wait up to 5 seconds before continuing, SHALL close the session with code `4234`, SHALL force-remove the container, and SHALL emit `StopEvent(reason)`. It SHALL NOT remove the entry from the client map. A container die event SHALL only emit `StopEvent(PROCESS_KILLED)` and SHALL NOT mark the handle terminal.

#### Scenario: Order is shutdown, close, remove, reason

- **WHEN** `terminate(id, reason)` runs
- **THEN** the shutdown request is sent, the session is closed with `4234`, the container is removed, and `StopEvent(reason)` is emitted

#### Scenario: Shutdown timeout continues

- **WHEN** the shutdown request does not answer within 5 seconds
- **THEN** termination logs and proceeds to close and remove

#### Scenario: Map entry is not removed by terminate

- **WHEN** `terminate` completes
- **THEN** the handle remains cached until the sweep evicts it

#### Scenario: Self-update restart survives die signal

- **WHEN** a container dies because of a self-update restart
- **THEN** a `StopEvent(PROCESS_KILLED)` is emitted and the cached handle is not torn down

