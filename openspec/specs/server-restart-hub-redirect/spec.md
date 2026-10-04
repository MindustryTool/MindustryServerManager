# server-restart-hub-redirect Specification

## Purpose
TBD - created by archiving change redirect-players-to-hub-on-restart. Update Purpose after archive.
## Requirements
### Requirement: Server redirect on restart/shutdown
The system SHALL redirect all active players to the central Hub server (`server.mindustry-tool.com:10002`) prior to shutting down or restarting the server process, unless the server is already a Hub instance.

#### Scenario: Server restart with active players on a non-hub server
- **WHEN** `Control.unload(event)` is invoked with `event.exit == true`, `Cfg.IS_HUB` is `false`, and there are connected players in `Groups.player`
- **THEN** The system sends an announcement message to all connected players indicating server restart and redirect
- **AND** The system dispatches `Call.connect(player.con, hostAddress, 10002)` for each connected player
- **AND** The system pauses for a grace period (e.g. 2.5 seconds) to allow packets to transmit before `System.exit(1)` is executed

#### Scenario: Server restart on Hub server
- **WHEN** `Control.unload(event)` is invoked and `Cfg.IS_HUB` is `true`
- **THEN** The system skips player redirection and proceeds directly with the shutdown sequence

#### Scenario: Server restart with no connected players
- **WHEN** `Control.unload(event)` is invoked and `Groups.player.size() == 0`
- **THEN** The system skips player redirection and delays, proceeding directly to unload and exit

#### Scenario: Redirection host resolution failure
- **WHEN** `server.mindustry-tool.com` cannot be resolved or an error occurs during redirect dispatch
- **THEN** The system logs an error without preventing the server from completing shutdown and calling `System.exit(1)`

