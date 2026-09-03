# recent-player-tracking

## Purpose
Track recently joined players with minimal metadata (IP, name, UUID) decoupled from active Session objects, with automatic 12-hour expiration after leaving and gateway query support.

## Requirements

### Requirement: Recent Player DTO
The system SHALL provide a `RecentPlayerDto` class in the `dto` module containing `ip` (String), `name` (String), `uuid` (String), and `joinedAt` (long). The class SHALL NOT reference Mindustry `Player`, `Session`, or server engine objects.

#### Scenario: DTO serialization and instantiation
- **WHEN** a `RecentPlayerDto` is created with ip, name, uuid, and joinedAt timestamp
- **THEN** it can be serialized to and deserialized from JSON without cyclic or engine-internal dependencies

### Requirement: Recent Player Tracking
`SessionService` (or a dedicated component) SHALL maintain an in-memory collection of recent players. When a player joins, their `ip`, `name`, `uuid`, join timestamp (`joinedAt`), and `leftAt` (0) SHALL be recorded. When a player leaves, their `leftAt` timestamp SHALL be updated. Players currently on the server SHALL NOT be removed from the collection.

#### Scenario: Player joins server
- **WHEN** a player connects and triggers the join lifecycle
- **THEN** a `RecentPlayerDto` record with their IP, name, UUID, join timestamp, and `leftAt = 0` is saved to the recent players collection
- **AND** the player remains in the collection while active on the server

#### Scenario: Player leaves server
- **WHEN** a player disconnects from the server
- **THEN** their `leftAt` timestamp is recorded on the `RecentPlayerDto` record
- **AND** the record is retained for 12 hours after leave before expiration

### Requirement: Automatic Expiration
Entries in the recent players tracking collection SHALL NOT expire while the player is still connected to the server. For players who have left the server, entries SHALL automatically expire and be removed 12 hours after their `leftAt` timestamp.

#### Scenario: Periodic expiration cleanup
- **WHEN** a scheduled cleanup interval occurs or when recent players are requested
- **THEN** any entry for a disconnected player whose `leftAt` timestamp is older than 12 hours (43200000 milliseconds) is removed from the collection
- **AND** any player currently active on the server is preserved regardless of join time

### Requirement: Gateway Query for Recent Players
The plugin `ApiGateway` SHALL register a message handler `get-recent-players` that returns the list of unexpired `RecentPlayerDto` objects.

#### Scenario: Gateway receives get-recent-players message
- **WHEN** `ApiGateway` handles a `get-recent-players` request
- **THEN** it returns a List of `RecentPlayerDto` containing all currently active players and players who left within the last 12 hours
