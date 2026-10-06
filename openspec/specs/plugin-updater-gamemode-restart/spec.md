# plugin-updater-gamemode-restart Specification

## Purpose

Manages gamemode-aware restart scheduling for the single managed plugin (controller, `mods/plugin.jar`) with bundle hash-based update checks.

## Requirements

### Requirement: Triggering Update Download and Server Restart

The system SHALL track exactly one managed plugin (the controller, `mods/plugin.jar`); the `PluginData` multi-plugin abstraction SHALL NOT exist. The system SHALL poll for plugin updates on a 1-minute fixed delay schedule (`@Schedule(delay = 1, fixedDelay = 1, unit = TimeUnit.MINUTES)`), query the bundled jar hash and download bytes via WebSocket messages sent to the server manager through `ApiGateway`, save the new hash setting, and fire `UnloadServerEvent(true)` once the conditions for restart are satisfied. If the WebSocket connection is disconnected or the request fails, the system SHALL catch the failure, log a warning, skip the update cycle, and retry on the next scheduled tick without making direct external HTTP calls. Other plugins on the node are user-managed and SHALL be ignored by the updater.

#### Scenario: Sandbox 30-minute countdown expires

- **WHEN** the scheduled 30-minute timestamp is reached in sandbox mode
- **THEN** the system requests the bundled controller plugin bytes from the server manager via WebSocket, writes the file to disk, and triggers server restart

#### Scenario: GameOverEvent occurs in non-sandbox mode

- **WHEN** `EventType.GameOverEvent` is fired and a restart is waiting for game over with a pending update
- **THEN** the system requests the bundled controller plugin bytes from the server manager via WebSocket, writes the file to disk, and triggers server restart

#### Scenario: All players disconnect with pending update

- **WHEN** `Groups.player.isEmpty()` is true and a pending update exists
- **THEN** the system immediately requests the bundled controller plugin bytes from the server manager via WebSocket, writes the file to disk, and triggers server restart without waiting for the timer or game over

#### Scenario: Periodic 1-minute update check discovers new bundle hash

- **WHEN** the update check executes on its 1-minute scheduled delay and queries the bundle hash via `ApiGateway`
- **THEN** the system compares the returned hash against the stored hash and records a pending update if newer

#### Scenario: Server manager unreachable during scheduled check

- **WHEN** the update check executes while `ApiGateway` is disconnected from the server manager
- **THEN** the failure is caught, an error or warning is logged, no unhandled exception aborts the scheduler, and no direct WAN HTTP request is attempted