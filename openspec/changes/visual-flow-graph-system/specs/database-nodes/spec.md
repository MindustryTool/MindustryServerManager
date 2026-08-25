## ADDED Requirements

### Requirement: Generic database node set
The system SHALL provide DB Query, Insert, Update, Delete, and Transaction nodes wrapping the existing SQLite ORM asynchronously; results SHALL surface as typed row collections compatible with the graph type system (e.g., `List<Map<String,Value>>`).

#### Scenario: Parameterized query flows onward
- **WHEN** a Query node runs `SELECT ... WHERE name = :name` bound to a String variable
- **THEN** downstream ForEach/Get Property nodes consume typed rows without raw JDBC leakage

### Requirement: Main-thread safety
Database operations SHALL execute on the ORM's dedicated executor and MUST NOT block the Mindustry main thread; results resume graph continuations on the main thread.

#### Scenario: Large query during traffic
- **WHEN** a query scanning thousands of rows runs while players interact
- **THEN** tick timing is unaffected and rows arrive via Await-style resumption

### Requirement: Parameterization only
User-supplied values SHALL bind exclusively through parameters; constructing SQL by concatenating graph string values into statement text SHALL be rejected at validation time as a correctness guard against authoring mistakes.

#### Scenario: Concatenated query rejected
- **WHEN** a document wires a text variable directly into the query text input of a Query node
- **THEN** validation fails directing the author to the parameter binding port

### Requirement: Transaction semantics
The Transaction node SHALL execute its grouped write operations atomically, rolling back all writes on any failure and surfacing a typed transaction error otherwise.

#### Scenario: Rollback on failure
- **WHEN** the second write inside a Transaction fails
- **THEN** the first write is rolled back and the failure branch receives the error
