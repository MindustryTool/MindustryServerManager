# user-id-ban Specification

## Purpose

Account-level ban enforcement and administrative management by `userId` persisted in `Core.settings` without database overhead, kicking banned users on login and immediately if active.

## Requirements
### Requirement: Persistent userId ban storage in settings
The system SHALL store banned `userId` entries in `Core.settings` without requiring database tables or migrations.

#### Scenario: Server restart preserves banned accounts
- **WHEN** the server restarts
- **THEN** the set of banned `userId`s is restored from `Core.settings` into memory

#### Scenario: Banning a userId persists to settings
- **WHEN** a `userId` is added to the banned list
- **THEN** it is immediately persisted to `Core.settings` via `forceSave()`

#### Scenario: Unbanning a userId persists to settings
- **WHEN** a `userId` is removed from the banned list
- **THEN** it is removed from `Core.settings` and changes are saved

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
The system SHALL provide administrative commands to ban, unban, and list banned `userId`s.

#### Scenario: Admin bans a userId
- **WHEN** an admin runs `/userban <userId> [reason...]`
- **THEN** the `userId` is recorded as banned, any online player with that `userId` is kicked, and confirmation is logged

#### Scenario: Admin unbans a userId
- **WHEN** an admin runs `/userunban <userId>`
- **THEN** the `userId` is removed from the banned list and confirmation is returned

#### Scenario: Admin lists banned accounts
- **WHEN** an admin runs `/userbans`
- **THEN** the system displays all currently banned `userId` entries

