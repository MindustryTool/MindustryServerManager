# sqlite-orm

## Purpose

TBD - created by syncing change sqlite-orm-migration. Update Purpose after implementation.

## Requirements

### Requirement: Query builder API

`SQLiteDatabase` SHALL provide fluent query builders for SELECT, INSERT, UPDATE, and DELETE with the following shapes:

- `db.select(Column<?>...).from(Table<?>)` with optional `.join(Table<?>).on(Condition)`, `.where(Condition)`, `.orderBy(Order...)`, `.limit(int)`, `.offset(int)`, terminating in `fetch()`, `fetch(Class<T>)`, `fetchOne()`, `fetchOne(Class<T>)` or their async forms.
- `db.insert(Table<?>).set(Column<?>, value)...` terminating in `execute()` / `executeAsync()`, plus `.onConflictDoUpdate(Column<?> conflictTarget, Column<?>... updateColumns)` rendering a SQLite upsert.
- `db.update(Table<?>).set(Column<?>, value)...` with optional `.where(Condition)`, terminating in `execute()` / `executeAsync()`.
- `db.delete(Table<?>)` with optional `.where(Condition)`, terminating in `execute()` / `executeAsync()`. A DELETE with no `where` SHALL require an explicit `.all()` call or fail.

INNER JOIN is supported; LEFT/OUTER joins are not required. ORDER BY SHALL accept any number of `Column.asc()` / `Column.desc()` orders. LIMIT and OFFSET SHALL be supported together.

#### Scenario: Select with where, order, limit
- **WHEN** `db.select(Users.NAME).from(Users.TABLE).where(Users.ACTIVE.eq(true)).orderBy(Users.ID.desc()).limit(20).fetch()` executes
- **THEN** it returns up to 20 active user names ordered by id descending

#### Scenario: Insert with upsert
- **WHEN** `db.insert(Users.TABLE).set(Users.ID, 1L).set(Users.NAME, "x").onConflictDoUpdate(Users.ID, Users.NAME).execute()` executes
- **THEN** a row is inserted, and a subsequent identical insert updates `name` instead of failing on the primary key

#### Scenario: Update with where
- **WHEN** `db.update(Users.TABLE).set(Users.NAME, "y").where(Users.ID.eq(1L)).execute()` executes
- **THEN** only the matching row is updated and the affected row count is returned

#### Scenario: Delete requires scope
- **WHEN** `db.delete(Users.TABLE).execute()` is invoked without `.where()` or `.all()`
- **THEN** it fails with a clear exception and no rows are deleted
- **AND** `db.delete(Users.TABLE).all().execute()` deletes all rows

### Requirement: Conditions and expressions

`Column<T>` SHALL support comparison methods `eq`, `ne`, `gt`, `gte`, `lt`, `lte`, `in`, `notIn`, `like`, `isNull`, `isNotNull`. Conditions SHALL support `and(Condition)`, `or(Condition)`, and `not()`.

Nested composition SHALL render unambiguous SQL: `a.and(b.or(c))` renders as `a AND (b OR c)`; `not` renders as `NOT (...)`; flat chains of the same operator render without redundant parentheses.

#### Scenario: All comparison operators render
- **WHEN** each of eq/ne/gt/gte/lt/lte/in/notIn/like/isNull/isNotNull is used in a query
- **THEN** the rendered SQL contains the corresponding operator (`=`, `<>`, `>`, `>=`, `<`, `<=`, `IN`, `NOT IN`, `LIKE`, `IS NULL`, `IS NOT NULL`) with values as parameters

#### Scenario: Nested grouping is unambiguous
- **WHEN** `Users.ACTIVE.eq(true).and(Users.ID.gt(10L).or(Users.NAME.isNull()))` is used
- **THEN** the rendered WHERE is equivalent to `users.active = ? AND (users.id > ? OR users.name IS NULL)` with parameters in binding order

### Requirement: SQL rendering contract

Query construction SHALL NOT perform JDBC operations. Every query SHALL render to a `SqlQuery { sql, parameters }` where:

- All user-provided values are bound as `?` PreparedStatement parameters in deterministic order — values are NEVER concatenated into SQL text.
- Only table and column names from trusted `Table`/`Column` definitions are rendered inline, always qualified (e.g., `users.id`); SQLite syntax requires unqualified column names in `UPDATE`/upsert `SET` clauses and `INSERT` column lists, which SHALL render unqualified.
- `column.eq(null)` SHALL render as `column IS NULL` (documented behavior).
- LIMIT/OFFSET SHALL render as bound parameters (`LIMIT ? OFFSET ?`).

#### Scenario: Values are parameterized
- **WHEN** a where condition uses `Users.NAME.eq("Hau")` and `Users.ID.gt(10L)`
- **THEN** the SQL contains `users.name = ? AND users.id > ?` and the parameter list is `["Hau", 10]`

#### Scenario: Null equality is safe
- **WHEN** `Users.NAME.eq(null)` is used
- **THEN** the rendered SQL is `users.name IS NULL` with no parameters, never `= NULL`

### Requirement: Type mapping

The ORM SHALL support at least: String, Integer, Long, Short, Byte, Boolean, Float, Double, byte[], UUID, Instant, and enums. Mapping SHALL be consistent: Boolean as SQLite INTEGER 0/1, UUID as canonical TEXT, Instant as ISO-8601 TEXT, enums as TEXT name, byte[] as BLOB. NULL values SHALL round-trip as Java null without errors.

#### Scenario: All supported types round-trip
- **WHEN** a row is inserted with values of every supported type and then selected
- **THEN** each value compares equal to the original, including NULLs, and byte[] content is identical

#### Scenario: Boolean storage
- **WHEN** a boolean `true`/`false` is written
- **THEN** the stored value is SQLite INTEGER 1/0 and reads back as `true`/`false`

### Requirement: Row mapping

`RowMapper<T>` SHALL be a functional interface `T map(ResultSet rs) throws SQLException`. Mappers SHALL be registered per result type via `registerMapper(Class<T>, RowMapper<T>)`. Typed `fetch(Class<T>)`/`fetchOne(Class<T>)` SHALL use the registered mapper and fail with a clear exception when none is registered. Reflection SHALL NOT be used for mapping. Untyped `fetch()` SHALL return `List<Row>` where `Row` provides typed accessors by column name. `fetchOne()` SHALL return `Optional<T>`: empty for no result, value for exactly one, exception when more than one row matches.

#### Scenario: Registered mapper maps rows
- **WHEN** `db.registerMapper(User.class, rs -> new User(rs.getLong("id"), rs.getString("name")))` is called and a typed fetch runs
- **THEN** each returned User is constructed by the mapper with the row values

#### Scenario: Missing mapper fails clearly
- **WHEN** a typed fetch runs for a class with no registered mapper
- **THEN** it fails with a clear exception naming the missing mapper

#### Scenario: FetchOne semantics
- **WHEN** `fetchOne()` runs against a table with zero matching rows
- **THEN** it returns `Optional.empty()`
- **AND** when more than one row matches, it fails with a clear exception

### Requirement: Sync and async API parity

Every public I/O operation SHALL have a synchronous form and an asynchronous form returning `CompletableFuture`: `fetch`/`fetchAsync`, `fetchOne`/`fetchOneAsync`, `execute`/`executeAsync`, `transaction`/`transactionAsync`. Async forms SHALL execute the entire operation — including lazy initialization and connection acquisition — on the database executor; the caller thread SHALL NOT perform blocking database work before the future is returned. Async failures SHALL propagate as exceptional completion.

#### Scenario: Async select executes on the database executor
- **WHEN** `fetchAsync()` is called and the database executor records its executing thread
- **THEN** the query runs on the database executor thread, not the caller thread, and the future completes with the result

#### Scenario: Async failure propagates
- **WHEN** an async operation throws (e.g., syntax error, closed database)
- **THEN** the returned CompletableFuture completes exceptionally with the cause

### Requirement: Lazy initialization

Constructing `SQLiteDatabase` via `builder().path(...).executor(...).build()` SHALL NOT open a SQLite connection, create the database file or directory, load the JDBC driver, execute any SQL or PRAGMA, or start worker threads. Initialization SHALL happen exactly once at the first database operation, and SHALL be thread-safe: concurrent first-use operations must not initialize more than once and must all complete successfully.

#### Scenario: Construction performs no database work
- **WHEN** a `SQLiteDatabase` is built with a fresh path
- **THEN** no connection has been created, no file exists, no executor thread has started, and no SQL has executed

#### Scenario: First operation initializes exactly once
- **WHEN** many async and sync operations race on a never-used database
- **THEN** initialization occurs exactly once, all operations complete, and no partial or duplicated initialization is observable

### Requirement: Lazy connection acquisition

The ORM SHALL NOT create JDBC connections at construction. Each operation SHALL acquire a connection only when it needs one and release (close) it when done. Concurrent operations SHALL use separate connections; a single connection SHALL never be shared across unrelated concurrent operations. Each new connection SHALL carry the configured SQLite settings (busy timeout 3000 ms preserved from the existing `Database` component).

#### Scenario: First use creates the connection
- **WHEN** a query executes on a freshly built database
- **THEN** a connection is created for that operation and closed afterwards

#### Scenario: Concurrent operations use separate connections
- **WHEN** several async operations run concurrently
- **THEN** each completes successfully without connection-sharing errors or busy-timeout failures at normal load

### Requirement: Database executor

Database I/O SHALL run on a dedicated executor, never `ForkJoinPool.commonPool()`. The executor SHALL be configurable via `.executor(ExecutorService)`; when supplied by the caller, `SQLiteDatabase` SHALL NOT shut it down. When absent, a default executor SHALL be created lazily as part of initialization (no threads at construction) and SHALL be shut down by `close()`.

#### Scenario: Caller-supplied executor is respected and not shut down
- **WHEN** a caller-provided executor is used and the database is closed
- **THEN** async work ran on that executor and the executor remains usable afterwards

#### Scenario: Default executor is lazy and owned
- **WHEN** no executor is supplied
- **THEN** no executor thread exists before first use, async operations run on it, and `close()` shuts it down

### Requirement: Transactions

`db.transaction(Consumer<Transaction>)` (and `transactionAsync`) SHALL acquire one dedicated connection, disable auto-commit, run all builder operations in the body against that same connection, commit on success, and roll back on any exception before propagating it. The connection SHALL have auto-commit restored and be closed in all outcomes. Nested transactions SHALL be rejected with a clear exception. `transactionAsync` SHALL run the whole transaction on the database executor and complete the future exceptionally on failure.

#### Scenario: Commit on success
- **WHEN** a transaction body inserts and updates, then completes normally
- **THEN** both changes are committed and visible to later queries

#### Scenario: Rollback on exception
- **WHEN** a transaction body throws after writing
- **THEN** no change is visible afterwards and the exception propagates (exceptionally, for async)

#### Scenario: Connection state restored
- **WHEN** a transaction fails and a subsequent normal operation runs
- **THEN** the subsequent operation behaves normally (no stale auto-commit=false state)

### Requirement: Lifecycle

`SQLiteDatabase` SHALL implement `AutoCloseable`. `close()` SHALL prevent new operations with a clear exception, shut down only owned resources (default executor), never shut down caller-supplied executors, and be safe to call multiple times. Closing an uninitialized database SHALL be a no-op that does not initialize SQLite.

#### Scenario: Operations after close fail clearly
- **WHEN** any operation runs after `close()`
- **THEN** it fails with a clear exception indicating the database is closed

#### Scenario: Close before first use is safe
- **WHEN** a database is built and closed without any operation
- **THEN** no SQLite file is created, no initialization happens, and no error is thrown

#### Scenario: Close is idempotent
- **WHEN** `close()` is called multiple times
- **THEN** no error is thrown and owned resources are released once

### Requirement: Resource safety

Every operation SHALL close its `ResultSet`, `PreparedStatement`, and `Connection` exactly once via try-with-resources, including on failure and for async operations. A failed operation SHALL NOT leak resources, and a failed transaction SHALL NOT leave a connection checked out.

#### Scenario: Failure closes resources
- **WHEN** an operation fails mid-execution (sync or async)
- **THEN** no leaked connections or statements are observed on subsequent operations

### Requirement: Raw SQL escape hatch

`SQLiteDatabase` SHALL provide a narrow raw API — `raw(String sql, Object... params)` returning affected rows, `rawQuery(String sql, Object... params)` returning `List<Row>`, and `hasColumn(table, column)` — for intentional schema setup (DDL), SQLite-specific SQL the typed API cannot express, and admin tooling. Raw values SHALL still be bound as `?` parameters.

#### Scenario: DDL via raw
- **WHEN** `raw("CREATE TABLE IF NOT EXISTS ...")` runs
- **THEN** the table exists and subsequent typed queries work against it

#### Scenario: Raw values are parameterized
- **WHEN** `rawQuery` is called with bound parameters
- **THEN** the SQL text contains `?` placeholders and no value is concatenated
