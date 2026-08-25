## ADDED Requirements

### Requirement: Code node accepts arbitrary Java-like bodies
The Code node SHALL accept user-written Java-like statement/expression bodies with typed `input(name)` access to node inputs and declared outputs, compiled as an integral part of the graph (same pipeline, cache, and versioning as visual nodes). Because the plugin runs inside an isolated VM, code bodies compile directly against the full Mindustry/plugin classpath — no stub APIs, bytecode verification, or restricted classloaders.

#### Scenario: Loop-heavy logic in one node
- **WHEN** a Code body iterates all players comparing teams and messages teammates
- **THEN** it compiles into the graph class and executes like any other node, subject to budgets

#### Scenario: Full API access within the trust boundary
- **WHEN** a body uses low-level host facilities such as `Files.readAllBytes` or Mindustry internals
- **THEN** compilation succeeds and the code runs like any other plugin code

### Requirement: Stability budgets apply to code bodies
Generated fragment wrappers SHALL inject the same operation-budget checkpoints as visual loops; exceeding budget aborts only that execution with a node-attributed error. Budgets exist for server stability (main-thread protection), not security isolation.

#### Scenario: Infinite loop in code contained
- **WHEN** a body contains `while(true){}`
- **THEN** the injected budget aborts execution with a node-attributed GraphBudgetExceeded error within bounded time

### Requirement: Errors attribute to the code node
Exceptions thrown inside a code body SHALL be caught by generated error handling and reported through the standard structured error path identifying graph, node, and stack trace.

#### Scenario: Thrown exception attributed
- **WHEN** a body dereferences a null player reference
- **THEN** the structured error names the Code node and includes the original stack trace
