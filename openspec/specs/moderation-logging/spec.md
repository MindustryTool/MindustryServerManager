# moderation-logging Specification

## Purpose

Audit logging for all player kicks, account bans, and unbans into SQLite database tables (`kick_logs` and `ban_logs`) with actor attribution, target identifiers, reasons, and timestamps.

## Requirements

### Requirement: Database-backed ban audit logging
The system SHALL log all ban and unban events with their action, target identifier, actor, reason, and `created_at` timestamp to the `ban_logs` SQLite table.

#### Scenario: User ban logged
- **WHEN** a user ID is banned with a reason
- **THEN** a row is inserted into `ban_logs` with action "BAN", user_id, actor, reason, and current `created_at` timestamp

#### Scenario: User unban logged
- **WHEN** a user ID is unbanned
- **THEN** a row is inserted into `ban_logs` with action "UNBAN", user_id, actor, and current `created_at` timestamp

#### Scenario: Native player ban logged
- **WHEN** a player UUID or IP is banned via Mindustry's ban mechanism
- **THEN** a row is inserted into `ban_logs` capturing the uuid, ip, player name, actor, reason, and current `created_at` timestamp

### Requirement: Database-backed kick audit logging
The system SHALL log all player kick events with their network address, UUID, player name, actor, reason, and `created_at` timestamp to the `kick_logs` SQLite table, excluding disconnects or kicks caused by a ban.

#### Scenario: Player kick recorded
- **WHEN** a player is kicked from the server and the kick is not the result of a ban
- **THEN** an entry is recorded in `kick_logs` with the player's `ip`, `uuid`, last known `name`, `actor` (admin, "VOTE", or "SERVER"), kick `reason`, and current `created_at` timestamp

#### Scenario: Kick logging does not block game thread
- **WHEN** a kick event occurs
- **THEN** the kick log record is inserted without causing noticeable freeze or lag in the game loop

#### Scenario: Ban-induced kick suppressed from kick audit log
- **WHEN** a player is kicked because they were banned (by account ban or player ban)
- **THEN** the kick is not recorded into `kick_logs` to prevent duplicate moderation logs

### Requirement: Unified moderation event representation
The system SHALL dispatch moderation events conforming to a common `ModerationEvent` interface containing `actor`, `reason`, and `timestamp`.

#### Scenario: Moderation events implement common interface
- **WHEN** an account ban or player kick occurs
- **THEN** the dispatched event implements `ModerationEvent` providing non-null `actor`, `reason`, and `timestamp` values
