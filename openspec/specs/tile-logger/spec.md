# tile-logger

## Requirements

### Requirement: Log block placement events
The system SHALL record a log entry whenever a player completes placing a block. Each entry MUST include the player's UUID, player name, block name, tile X and Y coordinates, map name, action `place`, and the unix timestamp (milliseconds). Entries SHALL only be recorded for completed placements (`BlockBuildEndEvent` with `breaking == false` and `unit.isPlayer()`).

#### Scenario: Player places a block
- **WHEN** a player completes placing a block at tile (x, y)
- **THEN** the system stores a `place` entry with the player's UUID and name, the block name, x/y, current map name, and timestamp

#### Scenario: Non-player placement is not logged
- **WHEN** a block is placed by a non-player unit (e.g. AI)
- **THEN** no `place` entry is recorded

### Requirement: Log block destroy events
The system SHALL record a log entry whenever a block is destroyed. The entry MUST include the block name, tile X and Y coordinates, map name, action `destroy`, timestamp, and player attribution when known. Player attribution SHALL be resolved by pairing the destroy with the player who began breaking the same tile; when no player started breaking the tile (e.g. enemy or explosion destroyed it), the entry SHALL be recorded with the player fields unset.

#### Scenario: Player destroys a block they started breaking
- **WHEN** a player starts breaking a block at tile (x, y) and the block is subsequently destroyed
- **THEN** the system stores a `destroy` entry attributed to that player's UUID and name

#### Scenario: Block destroyed without a player breaking it
- **WHEN** a block at tile (x, y) is destroyed by a non-player cause
- **THEN** the system stores a `destroy` entry for that tile without player attribution

### Requirement: Keep tile logs across player disconnects
The system SHALL keep all recorded entries in memory (RAM) so that entries are not lost when the acting player disconnects. Entries SHALL be cleared when a new map loads, and SHALL NOT be guaranteed to survive a server restart.

#### Scenario: Player places a block and leaves the server
- **WHEN** a player places a block and then disconnects
- **THEN** the entry remains available in memory for later inspection

#### Scenario: Server restarts
- **WHEN** the server restarts
- **THEN** all tile log entries are lost (accepted; entries are RAM-only)

### Requirement: Clear tile logs on new map load
When a new map loads, the system SHALL remove all tile log records from memory.

#### Scenario: New map is loaded
- **WHEN** a new map loads (`WorldLoadEvent`)
- **THEN** all previously recorded tile log entries are removed from memory

### Requirement: Admin can toggle tile inspection
The system SHALL provide an admin-only client command `tilelog` that toggles inspect mode for the invoking admin. While inspect mode is enabled for an admin, tapping a tile SHALL print the recorded log entries for that exact tile to that admin's chat, most recent first (up to 5 entries). If the tile has no records, the system SHALL inform the admin. Tapping SHALL only trigger inspection for admins with inspect mode enabled, and MUST NOT log, broadcast, or alter the tile.

#### Scenario: Admin enables inspect mode
- **WHEN** an admin runs `/tilelog` and then taps a tile that has `place`/`destroy` records
- **THEN** the admin receives chat messages listing up to 5 most recent entries with action, player, block, and time

#### Scenario: Admin inspects a tile with no records
- **WHEN** an admin with inspect mode enabled taps a tile with no recorded entries
- **THEN** the system sends the admin a message indicating no records exist for that tile

#### Scenario: Non-admin cannot use the command
- **WHEN** a non-admin runs `/tilelog`
- **THEN** the command is rejected

#### Scenario: Admin disables inspect mode
- **WHEN** an admin runs `/tilelog` a second time
- **THEN** inspect mode is disabled for that admin and taps no longer print tile logs

### Requirement: TileLogger component lifecycle
The system SHALL integrate `TileLogger` into the plugin lifecycle: the in-memory store SHALL be initialized when the plugin starts, SHALL be cleared when a new map loads and when the plugin unloads, and no exceptions from logging SHALL propagate to the game thread (all failures logged and swallowed).

#### Scenario: Plugin initializes
- **WHEN** the plugin initializes with `TileLogger` registered
- **THEN** `TileLogger` is ready to record and inspect entries without error

#### Scenario: Plugin unloads
- **WHEN** the plugin unloads
- **THEN** the in-memory store is cleared and no error is raised

#### Scenario: Logging fails
- **WHEN** recording or inspecting a tile log entry throws an error
- **THEN** the error is logged and the game thread continues without interruption