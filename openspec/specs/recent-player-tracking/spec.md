# recent-player-tracking

## Purpose
Track recently joined players with minimal metadata (IP, name, UUID) decoupled from active Session objects, with automatic 30-minute expiration and gateway query support.

## Requirements

### Requirement: Recent Player DTO
The system SHALL provide a `RecentPlayerDto` class in the `dto` module containing `ip` (String), `name` (String), `uuid` (String), and `joinedAt` (long). The class SHALL NOT reference Mindustry `Player`, `Session`, or server engine objects.

#### Scenario: DTO serialization and instantiation
- **WHEN** a `RecentPlayerDto` is created with ip, name, uuid, and timestamp
- **THEN** it can be serialized to and deserialized from JSON without cyclic or engine-internal dependencies

### Requirement: Recent Player Tracking
`SessionService` (or a dedicated component) SHALL maintain an in-memory collection of recent player joins. When a player joins, their `ip`, `name`, `uuid`, and the current timestamp (`joinedAt`) SHALL be recorded into the collection without keeping a reference to the `Session` or `Player` instance.

#### Scenario: Player joins server
- **WHEN** a player connects and triggers the join lifecycle
- **THEN** a `RecentPlayerDto` record with their IP, name, UUID, and join timestamp is saved to the recent players collection
- **AND** subsequent updates to the active `Session` or player disconnection do not alter or drop the record until expiration

### Requirement: Automatic Expiration
Entries in the recent players tracking collection SHALL automatically expire and be removed 30 minutes after their `joinedAt` timestamp.

#### Scenario: Periodic expiration cleanup
- **WHEN** a scheduled cleanup interval occurs or when recent players are requested
- **THEN** any entry whose `joinedAt` is older than 30 minutes (1800000 milliseconds) is removed from the collection

### Requirement: Gateway Query for Recent Players
The plugin `ApiGateway` SHALL register a message handler `get-recent-players` that returns the list of unexpired `RecentPlayerDto` objects.

#### Scenario: Gateway receives get-recent-players message
- **WHEN** `ApiGateway` handles a `get-recent-players` request
- **THEN** it returns a List of `RecentPlayerDto` containing all tracked players who joined within the last 30 minutes
