# idle-tick-throttling Specification

## Purpose
Throttles the Mindustry dedicated server main game loop tick rate to reduce CPU consumption when the server is empty and paused.
## Requirements
### Requirement: Idle Tick Loop Throttling
The system SHALL throttle the main game loop to approximately 10 TPS by sleeping in `EventType.Trigger.update` when the game is paused and no players are connected, ignoring background network connections.

#### Scenario: Server is idle and paused
- **WHEN** the game state is paused and the connected player list is empty
- **THEN** the update trigger listener SHALL sleep for approximately 100 milliseconds per tick to achieve ~10 TPS, regardless of any active network sockets or queries

#### Scenario: Server is active or unpaused
- **WHEN** the game state is not paused or the player list is not empty
- **THEN** the update trigger listener SHALL not inject idle sleep and SHALL run at normal speed

### Requirement: Immediate Auto-Pause on Player Leave
The system SHALL pause the game state immediately when the last remaining player disconnects from the server.

#### Scenario: Last player leaves the game
- **WHEN** a player disconnects and the remaining connected player count becomes zero
- **THEN** the system SHALL set the game state to paused without waiting for scheduled polling

### Requirement: Immediate Unpause on Player Join
The system SHALL restore playing state and normal 60 TPS operation immediately when a player joins the server.

#### Scenario: Player joins paused server
- **WHEN** a player completes joining while the server is paused
- **THEN** the system SHALL set the game state to playing and run at 60 TPS

### Requirement: Auto-Pause on Map Load
The system SHALL check player count and pause the game state immediately upon loading or hosting a map when no players are connected.

#### Scenario: Map loaded with no players
- **WHEN** a world or map finishes loading (`WorldLoadEvent` or `PlayEvent`) and the connected player list is empty
- **THEN** the system SHALL set the game state to paused without waiting for player interaction

