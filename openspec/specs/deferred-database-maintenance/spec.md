# deferred-database-maintenance

## Purpose

TBD - created by syncing change optimize-startup-performance. Update Purpose after implementation.

## Requirements

### Requirement: Session table available during startup
The `sessions` table SHALL be created (or verified) before `SessionRepository` is considered ready during startup. The one-time stored-exp-counter migration SHALL NOT run on the critical initialization path.

#### Scenario: Table exists at load
- **WHEN** `SessionRepository.init()` completes
- **THEN** the `sessions` table exists and session reads/writes are usable
- **AND** no full-table migration has run during init

#### Scenario: Migration deferred off the critical path
- **WHEN** the plugin loads
- **THEN** `migrateStoredExpCounters()` does not block plugin load
- **AND** it executes in the background after load completes

### Requirement: Deferred migration is idempotent and safe
The background exp-counter migration SHALL only run when the migration flag is unset, SHALL set the flag on completion, and SHALL not race with live session writes.

#### Scenario: Already migrated
- **WHEN** the `EXP_RECALCULATED_5` setting is already present
- **THEN** the background task does nothing

#### Scenario: First run repairs counters
- **WHEN** the migration flag is absent and legacy session rows exist
- **THEN** the background task repairs the stored exp counters, sets `EXP_RECALCULATED_5`, and persists it

#### Scenario: Concurrent writes are unaffected
- **WHEN** the background migration runs while the periodic session flush or a player write is active
- **THEN** no session row is corrupted or lost

### Requirement: Unload cancels pending migration
The deferred migration task SHALL be cancellable so plugin unload does not leave a background migration running against a closed database.

#### Scenario: Unload during migration
- **WHEN** the plugin is destroyed while the background migration is pending or running
- **THEN** the migration task is cancelled before the database is closed
- **AND** the migration flag is left unset so the next boot can retry