## ADDED Requirements

### Requirement: Asynchronous daily bonus check
`DailyService` SHALL execute database calls to `DailyRepository.getLastLogin` and `DailyRepository.setLastLogin` asynchronously via `Tasks.io` when handling `SessionCreatedEvent`, ensuring database lookups and writes do not run on the main game tick thread.

#### Scenario: Daily bonus check runs off-thread
- **WHEN** `SessionCreatedEvent` is fired for a joined player
- **THEN** the daily login date query and write are executed inside `Tasks.io`
- **AND** bonus exp/coin granting and message notifications are dispatched to the player on completion via `Core.app.post`
