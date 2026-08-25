## ADDED Requirements

### Requirement: Versioned logical graph document
A graph SHALL persist as a single versioned JSON document containing: schema `version`, stable `id`, monotonic `revision`, variable declarations, nodes (`id`, `type`, type-specific payload), edges (`from`/`to` port addresses `nodeId.portName`), subgraph references, and an opaque `editor` blob; documents SHALL be stored in the SQLite database as the single source of truth.

#### Scenario: Document round-trips through the database
- **WHEN** a saved document is loaded from SQLite and re-serialized without edits
- **THEN** the logical sections are semantically identical and the editor blob is preserved byte-for-byte

### Requirement: Unknown schema versions rejected clearly
The parser SHALL accept exactly the schema versions it implements; a document carrying a newer or unknown `version` SHALL be rejected with a diagnostic naming the found and supported versions — no silent interpretation and no automatic migration.

#### Scenario: Future-version document rejected
- **WHEN** a document with `version` greater than the implemented schema loads
- **THEN** validation fails stating the unsupported version and listing supported versions

### Requirement: Editor state separation
ReactFlow-specific state (positions, zoom, selection, UI prefs) SHALL live only inside the `editor` key or equivalent isolated storage; the compiler SHALL ignore it entirely.

#### Scenario: Compiler ignores visual state
- **WHEN** two documents differ only in their `editor` blobs but share logical content
- **THEN** both produce the identical canonical form and therefore the same compile-cache key

### Requirement: Generic node vocabulary
The format SHALL define a small closed set of generic node types (event, call, get-property, set-property, construct, cast, if, switch, sequence, loop, for-each, get-variable, set-variable, return, schedule, delay, await, parallel, log, try-catch-finally, throw, retry, timeout, http-get/post/put/delete, db-query/insert/update/delete/transaction, code); domain behavior SHALL be expressed through payloads referencing registry entries, never through new node types per function.

#### Scenario: New API needs no new node type
- **WHEN** a newly registered function must be used
- **THEN** it appears as payload on an existing generic call node

### Requirement: Schema validation
Every parsed document SHALL be validated against its declared schema version: unknown node types, malformed port addresses, duplicate ids, dangling edges, and missing required fields MUST be rejected with structured diagnostics before any later pipeline stage.

#### Scenario: Dangling edge rejected
- **WHEN** an edge references a nonexistent node id
- **THEN** validation fails listing the edge and both endpoint addresses

### Requirement: Stable identifiers across edits
Node ids, port names, function ids, event ids, and variable names SHALL remain stable identifiers referenced by edges, source maps, diagnostics, snapshots, and debug streams.

#### Scenario: Identifiers survive unrelated edits
- **WHEN** a node is added elsewhere in the document and the graph recompiles
- **THEN** pre-existing node ids, port addresses, and variable names are unchanged across edges, source maps, and error attributions
