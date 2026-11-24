# map-records

## Purpose
Persist, prune, and display top 10 player records for maps across gamemodes with pause-aware duration calculation.
## Requirements
### Requirement: Persistent Storage of Map Records in SQLite
The system SHALL persist map completion records in an embedded SQLite database table named `map_records` using typed ORM schema definitions.
Each record SHALL contain:
- `id`: Unique identifier (INTEGER PRIMARY KEY)
- `map_name`: Map filename without extension (TEXT NOT NULL)
- `gamemode`: Active gamemode name (TEXT NOT NULL)
- `duration_ms`: Completion or survival duration in milliseconds (INTEGER NOT NULL)
- `players`: JSON-encoded array containing participant UUIDs and display names (TEXT NOT NULL)
- `created_at`: Milliseconds epoch timestamp when the record was achieved (INTEGER NOT NULL)

#### Scenario: Table initialization
- **WHEN** the plugin database initializes
- **THEN** the `map_records` table is created if it does not already exist without altering existing records

#### Scenario: Saving a map completion attempt
- **WHEN** a qualifying map completion event occurs (win in attack/flood or game over in survival)
- **THEN** an attempt record with map name, active gamemode, duration, participant JSON, and timestamp is stored in SQLite

### Requirement: Top 10 Attempts Retention Pruning
The system SHALL retain at most the 10 best attempts for each unique combination of map name and gamemode.
Ranking order SHALL be evaluated as follows:
- Attack and Flood gamemodes: ordered by `duration_ms` ascending (lowest completion time is best)
- Survival gamemode: ordered by `duration_ms` descending (highest survival time is best)

#### Scenario: Attempt qualifies within top 10
- **WHEN** a game finishes and fewer than 10 records exist, or its duration is better than the 10th recorded attempt
- **THEN** the attempt is inserted and any attempts ranking beyond the top 10 for that map and gamemode are pruned from the database

#### Scenario: Attempt does not qualify for top 10
- **WHEN** a game finishes and 10 better attempts already exist in the database for the map and gamemode
- **THEN** the attempt is not added to the top 10 records

### Requirement: Pause-Aware Map Duration Tracking
The system SHALL calculate map completion or survival duration using in-game simulation ticks (`Vars.state.tick / 60.0`), automatically excluding paused periods and scaling with simulation speed.

#### Scenario: Game is paused and resumed
- **WHEN** a game is paused during play and later resumed
- **THEN** simulation ticks do not increment during the pause and the recorded duration reflects only active gameplay time

### Requirement: In-Game Leaderboard Presentation and Command
The system SHALL provide in-game notifications and a custom menu to view stored map records, formatting duration in clean seconds without raw milliseconds.

#### Scenario: Map start or player join announcement
- **WHEN** a map begins or a player joins the server
- **THEN** the #1 best record for the current map and gamemode is displayed to players with clean seconds precision (e.g. `5m20s`), or a no-record notification if none exist

#### Scenario: Inspecting top 10 records via client command
- **WHEN** a player executes the `/records` command
- **THEN** a custom `ServerMenu` opens displaying the top 10 recorded attempts for the current map, including rank, duration formatted in clean seconds, relative achievement date (e.g. `2d ago`), and participant names

#### Scenario: Achieving a new record
- **WHEN** a qualifying attempt finishes and ranks as a new #1 record
- **THEN** a server-wide announcement broadcasts the new record time formatted in clean seconds to all players

### Requirement: Session Participation Bonus
The system SHALL reward connected players with an EXP bonus based on their active participation duration relative to the total map duration upon a winning game completion.

#### Scenario: Awarding win bonus to participating players
- **WHEN** a winning completion occurs
- **THEN** each active session receives an EXP gain bonus proportional to their time in the match capped at 100%

### Requirement: In-Memory Best Record Caching and Asynchronous Saving
`MapRecordService` SHALL cache the best record for the current map in memory upon map start, and serve `buildRankString` on player join from this cached in-memory record without querying SQLite. When a game completes, record validation, saving, and pruning via `MapRecordRepository.saveAttempt` SHALL run asynchronously inside `Tasks.io`.

#### Scenario: Player joins during a match
- **WHEN** a player joins and receives the current map record announcement
- **THEN** the message is built using the in-memory cached record without issuing a database query

#### Scenario: Match completes
- **WHEN** a game reaches game over or completion
- **THEN** the attempt persistence and pruning logic executes asynchronously inside `Tasks.io`
- **AND** any new best record notifications are posted back to players on completion

### Requirement: One-Time Legacy Record Migration Purge
The system SHALL execute a one-time purge of existing `map_records` on initial deployment guarded by a persistent flag in `Core.settings` (`map_records_v2_migrated`). Once purged, the flag SHALL be persisted so subsequent server deploys and restarts never clear the table again.

#### Scenario: Initial deployment after update
- **WHEN** the server boots with `map_records_v2_migrated` not set
- **THEN** all existing corrupt records in `map_records` are cleared and `map_records_v2_migrated` is set to true and saved

#### Scenario: Subsequent deployments and server reboots
- **WHEN** the server restarts or is redeployed with `map_records_v2_migrated` already set to true
- **THEN** existing map records are retained intact and no purge occurs

### Requirement: Administrative Record Management Commands
The system SHALL provide administrative commands on both server console and client to purge map records on demand.

#### Scenario: Server console clears records
- **WHEN** an administrator runs `clearrecords` or `clearrecords all` on the console
- **THEN** all map records are deleted and in-memory best records are cleared

#### Scenario: Server console clears specific map
- **WHEN** an administrator runs `clearrecords <mapName>` on the console
- **THEN** records for only that map are deleted

#### Scenario: In-game administrator clears records
- **WHEN** an authorized admin executes `/records-clear all` or `/records-clear current`
- **THEN** the specified records are deleted and in-memory caches are updated

