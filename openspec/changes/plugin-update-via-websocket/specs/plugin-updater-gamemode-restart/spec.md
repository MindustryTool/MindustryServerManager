## MODIFIED Requirements

### Requirement: Triggering Update Download and Server Restart
The system SHALL poll for plugin updates on a 1-minute fixed delay schedule (`@Schedule(delay = 1, fixedDelay = 1, unit = TimeUnit.MINUTES)`), query plugin versions and download binaries via WebSocket messages sent to the server manager through `ApiGateway`, save updated version tracking settings, and fire `UnloadServerEvent(true)` once the conditions for restart are satisfied. If the WebSocket connection is disconnected or the request fails, the system SHALL catch the failure, log a warning, skip the update cycle, and retry on the next scheduled tick without making direct external HTTP calls.

#### Scenario: Sandbox 30-minute countdown expires
- **WHEN** the scheduled 30-minute timestamp is reached in sandbox mode
- **THEN** the system requests pending plugin downloads from the server manager via WebSocket, writes files to disk, and triggers server restart

#### Scenario: GameOverEvent occurs in non-sandbox mode
- **WHEN** `EventType.GameOverEvent` is fired and a restart is waiting for game over with pending updates
- **THEN** the system requests pending plugin downloads from the server manager via WebSocket, writes files to disk, and triggers server restart

#### Scenario: All players disconnect with pending updates
- **WHEN** `Groups.player.isEmpty()` is true and pending updates exist
- **THEN** the system immediately requests pending plugin downloads from the server manager via WebSocket, writes files to disk, and triggers server restart without waiting for the timer or game over

#### Scenario: Periodic 1-minute update check discovers new version
- **WHEN** `PluginUpdater.checkUpdate` executes on its 1-minute scheduled delay and queries the version via `ApiGateway`
- **THEN** `PluginData` queries the server manager over WebSocket, compares `updatedAt`, and enqueues a pending update if newer

#### Scenario: Server manager unreachable during scheduled check
- **WHEN** `PluginUpdater.checkUpdate` executes while `ApiGateway` is disconnected from the server manager
- **THEN** the failure is caught, an error or warning is logged, no unhandled exception aborts the scheduler, and no direct WAN HTTP request is attempted
