# server-auto-host

## Purpose
Autonomous discovery and startup of local official persistent servers after manager startup delay without depending on the backend API.

## Requirements

### Requirement: Delayed autonomous official server startup
The manager SHALL schedule a one-time startup task 1 minute after initialization. When triggered, the manager SHALL read local server configuration files directly from the server storage folder without making network requests to the backend API.

#### Scenario: One minute startup delay
- **WHEN** the manager completes initialization
- **THEN** the autonomous official server startup task is scheduled to execute after 1 minute

#### Scenario: Backend API independent
- **WHEN** the startup task executes
- **THEN** it reads server configurations strictly from local disk files and does not call the backend API

### Requirement: Filter official persistent servers
The manager SHALL filter discovered server configurations. The manager SHALL select only servers where `isOfficial` is true and `isAutoTurnOff` is false.

#### Scenario: Matching persistent official server
- **WHEN** a local server has `isOfficial == true` and `isAutoTurnOff == false`
- **THEN** the server is selected for startup

#### Scenario: Non-official server ignored
- **WHEN** a local server has `isOfficial == false` or null
- **THEN** the server is ignored and not auto-hosted

#### Scenario: Auto-turn-off official server ignored
- **WHEN** a local server has `isOfficial == true` and `isAutoTurnOff == true`
- **THEN** the server is ignored and not auto-hosted

### Requirement: Sequential hosting and idempotency
For each matching official server, the manager SHALL check whether the server is already hosting on the gateway. If already hosting, the manager SHALL skip hosting. If not hosting, the manager SHALL host the server sequentially on a background thread pool. If an error occurs during hosting of a server, the manager SHALL log the error and continue hosting remaining matching servers.

#### Scenario: Server already hosting
- **WHEN** a matching official server is already reported as hosting by the gateway
- **THEN** the manager skips calling host for that server

#### Scenario: Server not hosting
- **WHEN** a matching official server is not hosting
- **THEN** the manager calls host with the server config

#### Scenario: Failure isolation
- **WHEN** hosting one official server throws an exception
- **THEN** the manager logs the error and continues to host remaining matching official servers
