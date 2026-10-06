# plugin-gateway-url

## Purpose

Env-overridable plugin-to-manager gateway URL with dev/prod defaults, manager-side injection, and per-attempt plugin resolution.

## Requirements

### Requirement: Plugin gateway URL resolution

The plugin SHALL resolve its southbound gateway URI from the `PLUGIN_GATEWAY_URL` environment variable on every connect attempt, falling back by dev mode when blank. The manager SHALL resolve the same variable once per container create and inject the resolved value as `PLUGIN_GATEWAY_URL` into the game container.

#### Scenario: Explicit override wins on plugin

- **WHEN** `PLUGIN_GATEWAY_URL` is set to a non-blank `ws://` or `wss://` URL at connect time
- **THEN** the plugin dials exactly that URL and re-reads it fresh on every retry via its headers-style supplier pattern

#### Scenario: Blank plugin env falls back by dev mode

- **WHEN** `PLUGIN_GATEWAY_URL` is missing or blank and `Cfg.IS_DEVELOPMENT` is true
- **THEN** the plugin dials `ws://server-manager:8088/gateway`

#### Scenario: Blank plugin env in prod keeps old default

- **WHEN** `PLUGIN_GATEWAY_URL` is missing or blank and `Cfg.IS_DEVELOPMENT` is false
- **THEN** the plugin dials `ws://server.mindustry-tool.com:8089/gateway`

#### Scenario: Invalid scheme fails fast

- **WHEN** the resolved URL uses a non-`ws`/`wss` scheme
- **THEN** the plugin rejects it before dialing (via `JdkWsClient` builder) and logs the bad value without blocking plugin load

### Requirement: Manager injects resolved gateway URL

The manager SHALL inject a non-blank `PLUGIN_GATEWAY_URL` into every game container it creates, resolved from its own environment with the same dev/prod defaults.

#### Scenario: Manager injects resolved value

- **WHEN** `DockerNodeManager.create()` runs
- **THEN** the game container env contains `PLUGIN_GATEWAY_URL` set to the resolved URL (explicit env if non-blank, else DEV default `ws://server-manager:8088/gateway` when `Const.IS_DEVELOPMENT` else prod default)

#### Scenario: Prod unchanged without env

- **WHEN** `PLUGIN_GATEWAY_URL` is blank and `Const.IS_PRODUCTION` is true
- **THEN** the injected value equals the legacy prod URL `ws://server.mindustry-tool.com:8089/gateway`

### Requirement: Dev containers resolve manager by name

The manager SHALL NOT pin `server.mindustry-tool.com` / `api.mindustry-tool.com` to prod IPs when `Const.IS_DEVELOPMENT` is true, so the DEV default hostname `server-manager` resolves via Docker DNS. Local compose SHALL alias the manager as `server-manager` on the `mindustry-server` network.

#### Scenario: No prod pin in DEV

- **WHEN** a game container is created with `Const.IS_DEVELOPMENT` true
- **THEN** its `extraHosts` contains no `server.mindustry-tool.com` or `api.mindustry-tool.com` entries

#### Scenario: Prod pin retained

- **WHEN** a game container is created with `Const.IS_PRODUCTION` true
- **THEN** its `extraHosts` retains both prod IP pins as today

#### Scenario: Local alias resolves

- **WHEN** a game container on `mindustry-server` net dials `ws://server-manager:8088/gateway` under `docker-compose.local.yml`
- **THEN** Docker DNS resolves `server-manager` to the local manager via its network alias
