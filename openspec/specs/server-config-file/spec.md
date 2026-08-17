# server-config-file

## Purpose

Stores per-server runtime configuration in a single general-purpose `server.json` file in each server node's data directory, replacing `WEBSOCKET.txt`. Holds the gateway JWT and `StartServerDto` auto-host configuration, allowing the plugin to auto-host locally without WebSocket round-trips.

## Requirements

### Requirement: server.json file schema

The system SHALL replace `WEBSOCKET.txt` with a single JSON file `server.json` stored in each server node's data directory, whose schema is defined by the shared class `dto.ServerConfigDto`.

`ServerConfigDto` SHALL contain at least two fields: `jwt` (String, the gateway auth token) and `startServer` (`StartServerDto`, nullable, the map/mode/host-command used for auto-hosting). The class SHALL live in the `dto` module so both the orchestrator (`server`) and the plugin (`plugin`) compile against the same schema.

#### Scenario: File location is shared
- **WHEN** the orchestrator writes the config file for a node
- **THEN** it writes to `nodeManager.getFile(serverId, "server.json")`
- **AND** the plugin reads the same physical file at `Vars.dataDirectory.child("server.json")`

#### Scenario: DTO shared by both modules
- **WHEN** the `dto` module is compiled
- **THEN** it produces `dto.ServerConfigDto` with `jwt` and `startServer` fields
- **AND** both `server` and `plugin` reference that single class for reading and writing `server.json`

#### Scenario: StartServerDto reused
- **WHEN** a `server.json` is written or parsed
- **THEN** its `startServer` value is serialized/deserialized as a `dto.StartServerDto`

### Requirement: server.json written on host

`ServerService.host(ServerConfig)` SHALL write `server.json` on every host call, containing a fresh gateway JWT and a `StartServerDto` derived from the `ServerConfig` (its `hostCommand` and `mode`).

#### Scenario: Fresh file written when missing
- **WHEN** `ServerService.host(config)` runs and `server.json` does not exist
- **THEN** a `server.json` is created with the generated JWT and `startServer` built from `config.hostCommand` and `config.mode`

#### Scenario: File refreshed on every host
- **WHEN** `ServerService.host(config)` runs and `server.json` already exists
- **THEN** the file is overwritten so `startServer` matches the current `config` and the JWT is regenerated

### Requirement: JWT regeneration preserves startServer

`WsHandler.parseServerJwt()` SHALL, on token expiry, regenerate the JWT and write it back into `server.json` without losing the stored `startServer` config. If `server.json` cannot be read, a fresh `ServerConfigDto` SHALL be written.

#### Scenario: Expired token refreshed
- **WHEN** an expired JWT is presented and `server.json` contains a `startServer`
- **THEN** the file is rewritten with a fresh JWT
- **AND** the `startServer` value is unchanged

#### Scenario: Missing or corrupt file on expiry
- **WHEN** a JWT expires but `server.json` is missing or unparseable
- **THEN** a new `server.json` is written with the fresh JWT and no `startServer`

### Requirement: Plugin reads jwt from server.json

`Cfg.webSocketAuthToken()` SHALL return the `jwt` field read from `server.json` instead of reading `WEBSOCKET.txt`. `Cfg` SHALL expose a way to load the full `ServerConfigDto` (returning null when the file is absent).

#### Scenario: Token from server.json
- **WHEN** `Cfg.webSocketAuthToken()` is called and `server.json` exists with a `jwt`
- **THEN** it returns that `jwt` value

#### Scenario: No config file
- **WHEN** `server.json` does not exist
- **THEN** the `ServerConfigDto` loader returns null
- **AND** `Cfg.webSocketAuthToken()` returns null (websocket auth fails and reconnects are retried)

### Requirement: autoHost uses stored config

`ApiGateway.autoHost()` SHALL start hosting from the persisted `startServer` config in `server.json` by invoking the existing local `host(StartServerDto)` handler, instead of calling `hostRemoteServer(serverId)` when that config is present.

#### Scenario: Stored config used directly
- **WHEN** `autoHost()` runs, the server is not hosting, and `server.json` contains a non-null `startServer`
- **THEN** it invokes the local `host(startServer)` handler
- **AND** no `hostRemoteServer` request is sent

#### Scenario: Fallback when no config stored
- **WHEN** `autoHost()` runs and no `startServer` config is available in `server.json`
- **THEN** it keeps the existing fallback behavior of calling `hostRemoteServer(Control.SERVER_ID.toString())`

#### Scenario: No duplicate hosting
- **WHEN** `autoHost()` runs while hosting is already in progress
- **THEN** it skips hosting entirely, matching today's `isHosting` guard
