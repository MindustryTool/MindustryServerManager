## ADDED Requirements

### Requirement: In-Memory Best Record Caching and Asynchronous Saving
`MapRecordService` SHALL cache the best record for the current map in memory upon map start, and serve `buildRankString` on player join from this cached in-memory record without querying SQLite. When a game completes, record validation, saving, and pruning via `MapRecordRepository.saveAttempt` SHALL run asynchronously inside `Tasks.io`.

#### Scenario: Player joins during a match
- **WHEN** a player joins and receives the current map record announcement
- **THEN** the message is built using the in-memory cached record without issuing a database query

#### Scenario: Match completes
- **WHEN** a game reaches game over or completion
- **THEN** the attempt persistence and pruning logic executes asynchronously inside `Tasks.io`
- **AND** any new best record notifications are posted back to players on completion
