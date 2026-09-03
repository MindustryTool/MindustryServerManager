# plugin-updater-gamemode-restart Specification

## Purpose
Manages gamemode-aware restart scheduling (30-minute timer for sandbox, game-over trigger for competitive/wave gamemodes) and localized player notifications for pending plugin updates.
## Requirements
### Requirement: Gamemode-Aware Restart Scheduling for Pending Updates
When pending plugin updates are detected, the system SHALL determine the active gamemode and apply the corresponding restart policy. If the current gamemode is `sandbox`, the system SHALL schedule a server restart for 30 minutes from the time of discovery. If the current gamemode is any other mode (including survival, attack, pvp, flood, and custom gamemodes), the system SHALL defer the restart until the match concludes via `EventType.GameOverEvent`.

#### Scenario: Pending update discovered in sandbox mode
- **WHEN** `PluginUpdater` detects one or more pending plugin updates and the server is running `sandbox` gamemode
- **THEN** the system schedules a restart 30 minutes in the future and sets the restart state to scheduled

#### Scenario: Pending update discovered in non-sandbox mode
- **WHEN** `PluginUpdater` detects one or more pending plugin updates and the server is running a non-sandbox gamemode
- **THEN** the system marks the update as waiting for game over without setting a fixed 30-minute timer

### Requirement: Broadcast Restart Notifications to Online Players
The system SHALL broadcast a localized in-game notification to all currently connected players upon scheduling or deferring a restart due to pending updates.

#### Scenario: Broadcast in sandbox mode
- **WHEN** a 30-minute restart is scheduled for sandbox mode
- **THEN** all online players receive a localized announcement indicating the server will restart in 30 minutes

#### Scenario: Broadcast in non-sandbox mode
- **WHEN** a restart is deferred until game over for a non-sandbox gamemode
- **THEN** all online players receive a localized announcement indicating the server will restart after the current game finishes

### Requirement: Notify Newly Joined Players of Pending Restart
When a player connects to the server (`EventType.PlayerJoin`) while a plugin update restart is pending or scheduled, the system SHALL send a localized notification directly to that player.

#### Scenario: Player joins while sandbox restart is countdown scheduled
- **WHEN** a player joins the server and a sandbox restart is currently scheduled
- **THEN** the player receives a localized message stating the restart is scheduled with the remaining minutes until restart

#### Scenario: Player joins while waiting for game over
- **WHEN** a player joins the server and an update is deferred waiting for game over
- **THEN** the player receives a localized message stating the server will restart when the current game ends

#### Scenario: Player joins when no update is pending
- **WHEN** a player joins the server and no update or restart is pending
- **THEN** no update notification is sent to the player

### Requirement: Triggering Update Download and Server Restart
The system SHALL execute plugin downloads, save updated version tracking settings, and fire `UnloadServerEvent(true)` once the conditions for restart are satisfied.

#### Scenario: Sandbox 30-minute countdown expires
- **WHEN** the scheduled 30-minute timestamp is reached in sandbox mode
- **THEN** the system downloads pending plugins, writes files to disk, and triggers server restart

#### Scenario: GameOverEvent occurs in non-sandbox mode
- **WHEN** `EventType.GameOverEvent` is fired and a restart is waiting for game over with pending updates
- **THEN** the system downloads pending plugins, writes files to disk, and triggers server restart

#### Scenario: All players disconnect with pending updates
- **WHEN** `Groups.player.isEmpty()` is true and pending updates exist
- **THEN** the system immediately downloads pending plugins, writes files to disk, and triggers server restart without waiting for the timer or game over

