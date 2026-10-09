## ADDED Requirements

### Requirement: Player Connect SSE Ingestion
The system SHALL connect to the Server-Sent Events (SSE) endpoint `GET /api/v4/player-connect/sse` to receive real-time snapshots of active player-connect rooms. The room list in memory SHALL be updated whenever a new room event is received. The system SHALL ignore non-room handshake events without error.

#### Scenario: Successful room update via SSE
- **WHEN** the SSE endpoint emits an updated room list event containing room metadata
- **THEN** the system updates its internal room cache with the new list of rooms

#### Scenario: Handshake message received
- **WHEN** the SSE endpoint emits an initial handshake payload such as `"Connected"`
- **THEN** the system logs the connection confirmation and does not raise a deserialization error

#### Scenario: SSE disconnection and reconnection
- **WHEN** the SSE stream disconnects or encounters an I/O error
- **THEN** the system logs the failure and schedules an automatic reconnection attempt without crashing the server

### Requirement: Universal Hub Entry Model
The system SHALL provide a unified representation `HubEntry` covering both dedicated Mindustry servers (`Server`) and player-hosted rooms (`PlayerConnectRoom`).

#### Scenario: Unified mapping and sorting
- **WHEN** the hub server refreshes targets for map cores
- **THEN** it combines both Mindustry servers and Player Connect rooms into a single list sorted in descending order by active player count and maps them to available cores

### Requirement: In-Game Display of Universal Hub Entries
The system SHALL format and render text labels and map markers for both dedicated servers and Player Connect rooms on their respective cores.

#### Scenario: Displaying Player Connect room information
- **WHEN** a Player Connect room is assigned to a core
- **THEN** the core displays the room's name, active players, map name, gamemode, version, and indicators for whether it is secured or private

### Requirement: Player Interaction and Redirection
The system SHALL handle player tap events on cores corresponding to their target entry type.

#### Scenario: Player taps a dedicated Mindustry server core
- **WHEN** a player taps a core assigned to a standard Mindustry server
- **THEN** the system displays the server redirection menu and initiates the remote server host/connect sequence upon confirmation

#### Scenario: Player with Player Connect mod taps a room core
- **WHEN** a player taps a core assigned to a Player Connect room AND the client supports Player Connect (`hasPlayerConnect` returns true)
- **THEN** the system sends the `connect-player-connect` packet with the `roomId` to the player's connection

#### Scenario: Player without Player Connect mod taps a room core
- **WHEN** a player taps a core assigned to a Player Connect room AND the client does NOT support Player Connect (`hasPlayerConnect` returns false)
- **THEN** the system sends a notification to the player stating that the Player Connect mod is required to join

### Requirement: Discovery Snapshot Caching and Player Aggregation
The hub server discovery handler SHALL aggregate players from active Player Connect rooms and cache the discovery metadata for 30 seconds.

#### Scenario: Aggregating player counts in discovery response
- **WHEN** the hub server receives an ArcNet discovery ping packet
- **THEN** the returned player count includes both players from dedicated servers and players from active Player Connect rooms

#### Scenario: Discovery cache within 30 seconds
- **WHEN** consecutive discovery ping packets arrive within 30 seconds
- **THEN** the system responds with the cached discovery snapshot without querying the server gateway or re-summing active rooms
