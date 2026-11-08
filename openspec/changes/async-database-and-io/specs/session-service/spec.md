## ADDED Requirements

### Requirement: Asynchronous session loading and command locking
`SessionService` SHALL load session data asynchronously via `Tasks.io` without blocking the main game thread. While session data is loading, `Session.isLoading()` SHALL return true. If a player submits a command while their session is loading, `ClientCommandHandler` SHALL reject execution and send a localized `session.loading` message to the player.

#### Scenario: Player enters command during session load
- **WHEN** a player joins and executes a command before asynchronous session data loading completes
- **THEN** the command is rejected
- **AND** the player receives the localized `session.loading` message

#### Scenario: Session load completion unlocks commands
- **WHEN** the asynchronous session data query finishes
- **THEN** the session is populated on the main thread, `isLoading()` returns false, `SessionCreatedEvent` is fired, and subsequent commands execute normally

### Requirement: Asynchronous write-behind on player disconnect
When a player disconnects, `SessionRepository.onSessionRemoved` SHALL queue the session persistence write to `Tasks.io` asynchronously and remove the session from the in-memory cache, rather than executing a synchronous database write on the main thread.

#### Scenario: Player disconnects
- **WHEN** a player disconnects and `PlayerLeave` fires
- **THEN** session write is dispatched to `Tasks.io`
- **AND** the main game tick loop does not block on database execution
