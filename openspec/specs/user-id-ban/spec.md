# user-id-ban Specification

## Purpose

Account-level ban enforcement and administrative management by `userId` persisted in SQLite `banned_users` table with mandatory ban reasons and actor tracking, kicking banned users on login and immediately if active.

## Requirements

### Requirement: Persistent userId ban storage in database
The system SHALL store banned `userId` entries with their mandatory `reason`, `actor`, and `created_at` timestamp in the `banned_users` SQLite database table instead of `Core.settings`.

#### Scenario: Server restart preserves banned accounts
- **WHEN** the server restarts
- **THEN** the set of banned `userId`s is restored from the `banned_users` database table into memory

#### Scenario: Banning a userId persists to database
- **WHEN** a `userId` is added to the banned list with a valid non-empty reason
- **THEN** it is immediately inserted into the `banned_users` database table with its reason, actor, and timestamp

#### Scenario: Unbanning a userId persists to database
- **WHEN** a `userId` is removed from the banned list
- **THEN** it is deleted from the `banned_users` database table

### Requirement: Enforcement on account login
The system SHALL reject and disconnect any player connection that attempts to log in to an account whose `userId` is banned.

#### Scenario: Player logs into a banned account
- **WHEN** a player successfully completes authentication and receives a `LoginDto` with a banned `userId`
- **THEN** the system rejects the login
- **AND** immediately kicks the player with a localized account ban message

### Requirement: Immediate enforcement on online players
The system SHALL immediately kick any currently connected player whose active session has a banned `userId`.

#### Scenario: Active player account gets banned
- **WHEN** an administrator bans a `userId` that is currently logged in on the server
- **THEN** the online player associated with that `userId` is kicked immediately from the server

### Requirement: Administrative userId ban commands
The system SHALL provide administrative commands to ban, unban, and list banned `userId`s, requiring a non-empty reason when banning and recording the actor.

#### Scenario: Admin bans a userId with reason
- **WHEN** an admin runs `/userban <userId> <reason...>` with a non-empty reason
- **THEN** the `userId` is recorded as banned with the specified reason and actor
- **AND** any online player with that `userId` is kicked
- **AND** confirmation is returned to the admin

#### Scenario: Admin attempts to ban a userId without reason
- **WHEN** an admin runs `/userban <userId>` without specifying a reason
- **THEN** the command rejects the ban attempt
- **AND** no record is added to `banned_users`

#### Scenario: Admin unbans a userId
- **WHEN** an admin runs `/userunban <userId>`
- **THEN** the `userId` is removed from the banned list and confirmation is returned

#### Scenario: Admin lists banned accounts
- **WHEN** an admin runs `/userbans`
- **THEN** the system displays all currently banned `userId` entries

### Requirement: Mandatory ban reason enforcement
The system SHALL enforce that every ban operation specifies a non-null, non-blank reason string.

#### Scenario: Ban rejected when reason is missing or blank
- **WHEN** `UserBanService.ban(userId, reason, actor)` is called with a null or blank reason
- **THEN** the ban operation is rejected and returns false or fails loud
- **AND** the user is not added to the banned list
