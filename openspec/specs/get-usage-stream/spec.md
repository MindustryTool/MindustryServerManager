# get-usage-stream

## Purpose

Publisher-side usage event stream: get-usage listener, per-listener Docker stream, Docker-rate events, and fail-loud errors. Synced from change get-usage-event-stream.

## Requirements

### Requirement: Get-usage listener registration
The system SHALL expose a `get-usage` event listener on the backend-gateway channel with `ServerRef` params.

#### Scenario: Listen routes to usage handler
- **WHEN** backend sends `listen` with `event=get-usage` and `data={serverId}`
- **THEN** Server Manager routes to the get-usage handler and validates params

#### Scenario: Unknown event name rejected elsewhere
- **WHEN** backend sends `listen` with unknown event
- **THEN** system replies `listen-error` with `unknown event` detail

### Requirement: Per-listener Docker stream until unlisten
The system SHALL open one `ServerService.getUsage` stream per `listen` and keep it open until client `unlisten` or connection loss.

#### Scenario: Independent streams per listener
- **WHEN** two backends listen to `get-usage` with different `serverId`
- **THEN** each gets its own Docker stream and its own event sequence

#### Scenario: Unlisten ends only that stream
- **WHEN** client sends `unlisten` for one listen id
- **THEN** Server Manager sends `listen-ended`, closes that Docker `Closeable`, and keeps other streams open

#### Scenario: Connection loss cleans all streams
- **WHEN** channel closes with active get-usage streams
- **THEN** all Docker `Closeable` objects close and no further events send

### Requirement: Usage event emission at Docker rate
The system SHALL push each Docker `onUsage` value as an `event` frame with `NodeUsage` payload until closed.

#### Scenario: Event carries NodeUsage
- **WHEN** Docker reports usage for the server
- **THEN** Server Manager sends `event` with `event=get-usage`, `responseOf=listenId`, and `payload={cpu, ram, createdAt}`

#### Scenario: No push after close
- **WHEN** handle is closed via unlisten, complete, fail, or connection loss
- **THEN** further Docker callbacks for that stream send nothing

### Requirement: Fail-loud usage errors
The system SHALL send `listen-error` for unknown server and Docker failures.

#### Scenario: Unknown server rejected at listen
- **WHEN** `listen` names a missing server id
- **THEN** system replies `listen-error` with detail and creates no stream

#### Scenario: Docker mid-stream failure surfaces
- **WHEN** Docker `onError` fires for an active stream
- **THEN** system sends `listen-error` with detail and closes that stream
