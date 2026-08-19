# database-orm-migration

## Purpose

TBD - created by syncing change sqlite-orm-migration. Update Purpose after implementation.

## Requirements

### Requirement: All existing SQL migrated to the ORM

Every database statement that previously used `Database.prepare` / `Database.statement` or raw SQL in the plugin SHALL be rewritten on the ORM: the `sessions` and `player_logins` upserts, session read/leaderboard/exp-counter queries and updates, last-login queries, and schema setup. Schema setup SHALL use the typed table-creation API: `createTableIfNotExists` for `sessions` and `player_logins`, and `addColumnIfMissing` for the guarded legacy `totalExp` upgrade. Raw SQL SHALL remain only for the `getRank` correlated subquery (when it is kept) and the admin `/sql` command. Existing parameter semantics, result mapping, and transaction behavior SHALL be preserved.

#### Scenario: Repository queries run through the ORM
- **WHEN** `SessionRepository` and `DailyRepository` are exercised end-to-end
- **THEN** every operation executes through the ORM with the same inputs and outputs the previous JDBC code produced

#### Scenario: Schema setup uses the typed table-creation API
- **WHEN** repository `init()` runs
- **THEN** the `sessions` and `player_logins` tables are created via `createTableIfNotExists` with the same column definitions as before, and legacy databases missing `totalExp` are upgraded via `addColumnIfMissing`

#### Scenario: No raw DDL remains in repositories
- **WHEN** the migrated source tree is inspected
- **THEN** `SessionRepository` and `DailyRepository` contain no `CREATE TABLE` or `ALTER TABLE` raw SQL strings

### Requirement: Repository behavior preserved

`SessionRepository` and `DailyRepository` SHALL keep their public method signatures, write-behind caching, dirty-flag set, 10-second batched flush, immediate write on session removal, full flush on destroy, JSON blob serialization of `SessionData` into `sessions.data`, corrupt-row handling (log with uuid, preserve previously cached `SessionData`), and the deferred exp-counter migration behavior (off the startup path, idempotent via the `EXP_RECALCULATED_5` setting, cancellable on unload).

#### Scenario: Upsert semantics preserved
- **WHEN** a session is written twice for the same uuid
- **THEN** the second write updates the existing row (`data`, `totalExp`) instead of failing or duplicating

#### Scenario: Corrupt row does not reset the player
- **WHEN** a uuid's stored row exists but fails to deserialize
- **THEN** the failure is logged with the uuid and the previously cached `SessionData` is preserved

#### Scenario: Leaderboard preserved
- **WHEN** `leaderBoard(size)` runs
- **THEN** it returns rows ordered by `totalExp` descending, then `uuid` ascending, limited to `size`, identical to the previous SQL

#### Scenario: Last-login preserved
- **WHEN** `DailyRepository.getLastLogin` / `setLastLogin` run
- **THEN** the same dates round-trip as before, and a first-ever login creates a record without a bonus

### Requirement: Startup ordering preserved

The `sessions` table SHALL still exist when `SessionRepository.init()` returns (per `deferred-database-maintenance`), and the stored-exp-counter migration SHALL still run deferred in the background after load. Laziness introduced by the ORM SHALL NOT move or remove these guarantees: the first repository initialization triggers lazy ORM initialization, and the sessions table is created before reads/writes are usable.

#### Scenario: Table ready at repository init
- **WHEN** the plugin loads and `SessionRepository.init()` completes
- **THEN** the `sessions` table exists and session reads/writes are usable

#### Scenario: Migration still deferred and cancellable
- **WHEN** the plugin loads
- **THEN** `migrateStoredExpCounters()` does not block plugin load, runs in the background, and is cancelled before the database closes on unload

### Requirement: Obsolete database helpers removed

The old JDBC helper surface of `plugin/database/Database` — `prepare(...)`, `statement(...)`, `hasRow(...)`, `hasColumn(...)` and the raw SQL strings they served — SHALL be removed once their callers are migrated. `Database` SHALL remain the `@Component` seam owning the `SQLiteDatabase`: `@Init` creates it (lazily internally), repositories obtain it via `Database`, and `@Destroy close()` SHALL close the ORM and perform the existing plugin-classloader JDBC driver deregistration. No two competing database access systems SHALL remain; the final codebase SHALL contain no `PreparedStatement`/`Statement`/`ResultSet`/`DriverManager` usage outside `plugin/orm/**` and the intentional raw cases.

#### Scenario: No JDBC remains in repositories
- **WHEN** the migrated source tree is inspected
- **THEN** `SessionRepository` and `DailyRepository` contain no JDBC types or SQL string literals for queries (schema DDL strings in repositories are allowed)

#### Scenario: Plugin lifecycle preserved
- **WHEN** the plugin unloads
- **THEN** the ORM is closed and JDBC drivers belonging to the plugin classloader are deregistered, as before

### Requirement: Admin SQL command preserved

The admin `/sql` console command SHALL keep executing operator-supplied SQL and printing results (rows or affected-row count) to the console, now routed through the ORM's raw escape hatch. This command SHALL remain the only place arbitrary SQL text is accepted.

#### Scenario: Admin SQL runs and prints results
- **WHEN** an admin runs `/sql SELECT ...`
- **THEN** the query executes through the raw hook and its rows are printed to the console

### Requirement: Migration regression tests

For each migrated repository operation, tests SHALL cover: normal results, empty results, null values, multiple rows, updates affecting zero rows, updates affecting multiple rows, deletes, upserts, joins (when present), and the edge cases defined by existing application behavior. Tests SHALL run against isolated temp-file SQLite databases and SHALL NOT touch the production database at `./config/database`.

#### Scenario: Repository regression suite passes
- **WHEN** the plugin test suite runs
- **THEN** repository regression tests exercising the migrated operations pass against fresh temp databases
