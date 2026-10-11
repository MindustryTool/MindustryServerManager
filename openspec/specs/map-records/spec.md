# map-records

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
The system SHALL exclude paused game time from the tracked map duration calculation across all gamemodes.

#### Scenario: Game is paused and resumed
- **WHEN** a game is paused during play and later resumed
- **THEN** the duration spent in paused state is subtracted from the final calculated map duration

### Requirement: In-Game Leaderboard Presentation and Command
The system SHALL provide in-game notifications and a custom menu to view stored map records.

#### Scenario: Map start or player join announcement
- **WHEN** a map begins or a player joins the server
- **THEN** the #1 best record for the current map and gamemode is displayed to players, or a no-record notification if none exist

#### Scenario: Inspecting top 10 records via client command
- **WHEN** a player executes the `/records` command
- **THEN** a custom `PluginMenu` opens displaying the top 10 recorded attempts for the current map, including rank, time, date, and participant names

#### Scenario: Achieving a new record
- **WHEN** a qualifying attempt finishes and ranks as a new #1 record
- **THEN** a server-wide announcement broadcasts the new record time to all players

### Requirement: Session Participation Bonus
The system SHALL reward connected players with an EXP bonus based on their active participation duration relative to the total map duration upon a winning game completion.

#### Scenario: Awarding win bonus to participating players
- **WHEN** a winning completion occurs
- **THEN** each active session receives an EXP gain bonus proportional to their time in the match capped at 100%
