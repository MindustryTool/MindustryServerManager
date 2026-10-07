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

### Requirement: Disconnect-age clock lifecycle

The system SHALL maintain `lastDisconnectAt` per gateway client: set to create time at build, cleared to `null` on open (including overwrite opens), set to current time only on close of the current session. Stale closes SHALL NOT touch the clock.

#### Scenario: Never-linked client carries create time

- **WHEN** a client is created via `of(id)` and no open has occurred
- **THEN** `lastDisconnectAt` equals create time

#### Scenario: Open clears the clock

- **WHEN** `onOpen` succeeds, including an overwrite of a dead session
- **THEN** `lastDisconnectAt` is `null`

#### Scenario: Close starts the clock

- **WHEN** `onClose` for the current session runs
- **THEN** `lastDisconnectAt` is set to close time

#### Scenario: Stale close leaves the clock alone

- **WHEN** `onClose` arrives for a non-current session
- **THEN** `lastDisconnectAt` is unchanged

#### Scenario: Reconnect restarts grace

- **WHEN** a client reopens after a disconnect
- **THEN** the prior disconnect time is discarded and the orphan kill uses the new cycle

### Requirement: Disconnect terminate

The system SHALL terminate a gateway client with reason `NOT_CONNECTED` when `lastDisconnectAt != null`, current time is after `lastDisconnectAt` plus 3 minutes, the socket is still closed, and the container is still running. Container presence SHALL be taken from the batched running set of the sweep. Termination SHALL NOT remove the entry from the client map; the sweep evicts it once the container is gone.

#### Scenario: Closed past 3min with running container terminates

- **WHEN** a client stays socket-closed for more than 3 minutes while its container still runs
- **THEN** the system terminates it with `NOT_CONNECTED`

#### Scenario: Connected never terminates

- **WHEN** the socket is open at check time
- **THEN** no termination occurs, even if no app messages arrived for more than 3 minutes

#### Scenario: Never-linked client terminates after 3min

- **WHEN** a client never opened, its create time is more than 3 minutes ago, and its container still runs
- **THEN** the system terminates it with `NOT_CONNECTED`

#### Scenario: Reconnected client survives old clock

- **WHEN** a client disconnected, reconnected, and the new session is still open at 3min past the old close
- **THEN** no termination occurs

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

The scheduler SHALL evict a cached `GatewayClient` when its socket is closed and its container is not running, using a single batched container list per sweep. The eviction branch SHALL run before the orphan-kill branch within the same sweep. Eviction SHALL be treated as memory hygiene only and SHALL NOT represent a lifecycle transition.

#### Scenario: Socket closed and container gone evicts

- **WHEN** a cached handle has a closed socket and its container is absent from the batched running set
- **THEN** it is removed from the client map

#### Scenario: Running container keeps the handle

- **WHEN** a cached handle's container is present in the batched running set
- **THEN** it is not evicted by the container-gone branch

#### Scenario: One container list per sweep

- **WHEN** a sweep runs
- **THEN** at most one container-listing call is made for all cached handles

#### Scenario: Eviction precedes orphan kill

- **WHEN** a handle's container was removed by a prior terminate
- **THEN** the sweep evicts it instead of terminating it again

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

