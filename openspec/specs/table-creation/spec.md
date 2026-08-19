# table-creation

## Purpose

TBD - created by syncing change orm-table-creation. Update Purpose after implementation.

## Requirements

### Requirement: Typed table definition

`Column<T>` SHALL carry optional DDL metadata describing a column's role in schema creation: `primaryKey()`, `notNull()`, and `defaultValue(T)`. The metadata SHALL be immutable and applied via builder methods that return new `Column` instances; query-side behavior (`name()`, `table()`, `type()`) SHALL be unaffected. SQLite column types SHALL be derived from the mapped Java type consistently with query binding: String/UUID/Instant/enum as `TEXT`, Integer/Long/Short/Byte/Boolean as `INTEGER`, Float/Double as `REAL`, byte[] as `BLOB`.

#### Scenario: Column carries DDL metadata
- **WHEN** a column is defined as `TABLE.column("id", Long.class).primaryKey().notNull().defaultValue(0L)`
- **THEN** the column reports primary-key and not-null status and a default of `0L`, and still reports its name, table, and Java type

#### Scenario: Type derivation matches query binding
- **WHEN** the SQLite column type is requested for each supported Java type
- **THEN** String, UUID, Instant, and enums map to `TEXT`; Integer, Long, Short, Byte, and Boolean map to `INTEGER`; Float and Double map to `REAL`; byte[] maps to `BLOB`

### Requirement: Create table if not exists

`SQLiteDatabase` SHALL provide `createTableIfNotExists(Table<?> table, Column<?>... columns)` that executes `CREATE TABLE IF NOT EXISTS <table> (<col> <TYPE> [PRIMARY KEY] [NOT NULL] [DEFAULT <literal>], ...)` with columns in argument order. It SHALL be idempotent: running it against an existing table with the same schema SHALL be a no-op and SHALL NOT drop or alter existing data.

#### Scenario: Creates the table with the exact schema
- **WHEN** `db.createTableIfNotExists(table, idColumn.primaryKey(), nameColumn.notNull(), totalColumn.defaultValue(0L))` executes
- **THEN** the table exists with columns `id INTEGER PRIMARY KEY`, `name TEXT NOT NULL`, and `total INTEGER DEFAULT 0`

#### Scenario: Idempotent on existing table
- **WHEN** `createTableIfNotExists` is called twice with the same columns
- **THEN** the second call succeeds and the table's schema and data are unchanged

### Requirement: Add column if missing

`SQLiteDatabase` SHALL provide `addColumnIfMissing(Table<?> table, Column<?> column)` that adds the column via `ALTER TABLE <table> ADD COLUMN <col> <TYPE> [NOT NULL] [DEFAULT <literal>]` only when the column does not already exist, and SHALL be a no-op when the column exists. This provides the typed equivalent of the guarded legacy schema upgrade path.

#### Scenario: Adds a missing column
- **WHEN** a table created without the `totalExp` column is passed to `addColumnIfMissing` with the `totalExp` column definition
- **THEN** the column is added with the derived type and default

#### Scenario: No-op when column exists
- **WHEN** `addColumnIfMissing` is called for a column that already exists
- **THEN** no `ALTER TABLE` is executed and the schema is unchanged

### Requirement: Typed DDL rendering

Typed DDL SHALL render through the SQL renderer and SHALL NOT use bound `?` parameters: table and column names come from trusted `Table`/`Column` definitions, SQLite types from the centralized type derivation, and defaults render inline as literals (numbers as decimal literals, booleans as `1`/`0`, text values single-quoted, and no `DEFAULT` clause for a null default). A `byte[]` column SHALL NOT be used as a default (SQLite does not support BLOB defaults).

#### Scenario: Default values render as literals
- **WHEN** a column has default `0L` and another has default `"new"`
- **THEN** the rendered DDL contains `DEFAULT 0` and `DEFAULT 'new'` inline, with no placeholders

#### Scenario: Null default emits no clause
- **WHEN** a column has no default value
- **THEN** the rendered DDL contains no `DEFAULT` clause for that column
