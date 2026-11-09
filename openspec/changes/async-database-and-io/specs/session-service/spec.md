## ADDED Requirements

### Requirement: Asynchronous write-behind on player disconnect
When a player disconnects, `SessionRepository.onSessionRemoved` SHALL queue the session persistence write to `Tasks.io` asynchronously and remove the session from the in-memory cache, rather than executing a synchronous database write on the main thread.

#### Scenario: Player disconnects
- **WHEN** a player disconnects and `PlayerLeave` fires
- **THEN** session write is dispatched to `Tasks.io`
- **AND** the main game tick loop does not block on database execution
