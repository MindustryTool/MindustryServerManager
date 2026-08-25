## ADDED Requirements

### Requirement: Graph-defined reusable functions
Users SHALL define subgraphs with declared input/output parameters and local variables; compiled subgraphs SHALL appear in the function registry as callable entries invoked through the standard generic Call node.

#### Scenario: Subgraph appears as call target
- **WHEN** a subgraph `IsPlayerAllowed(player) → allowed:Boolean` is published
- **THEN** it is discoverable via the function search API and usable from any graph's Call node

### Requirement: Parameter, return, and variable support
Subgraphs SHALL support typed parameters, explicit Return nodes for outputs, local variable scope, documentation, and version addressing by content hash; callers MAY pin a specific version.

#### Scenario: Caller pins version
- **WHEN** a caller references `graph:IsPlayerAllowed@<hash1>` and a new revision `<hash2>` is published
- **THEN** the pinned caller keeps executing hash1 until explicitly upgraded

### Requirement: Recursion safety
Direct or mutual recursion SHALL be permitted only where each cycle contains an async boundary (Delay/Await); purely synchronous recursive cycles MUST be rejected at compile time; accepted recursion is additionally depth-capped at runtime.

#### Scenario: Synchronous recursion rejected
- **WHEN** a subgraph calls itself synchronously on its only path
- **THEN** compilation fails explaining the async-boundary requirement

### Requirement: Async subgraphs
Subgraphs containing Await/Delay SHALL expose Future-typed returns consumed via Await by callers, resuming on the main thread per the execution model.

#### Scenario: Caller awaits async subgraph
- **WHEN** a graph calls an async subgraph and Awaits its result
- **THEN** the continuation resumes on the main thread with the subgraph output

### Requirement: Independent compilation and caching
Each subgraph SHALL compile as its own cacheable artifact with its own fingerprint; callers revalidate against referenced subgraph hashes so editing a subgraph invalidates exactly its callers.

#### Scenario: Subgraph edit recompiles only callers
- **WHEN** subgraph S is edited and graphs A (uses S), B (uses S), C (does not use S) are loaded
- **THEN** only A and B revalidate/recompile while C's compiled artifact stays valid
