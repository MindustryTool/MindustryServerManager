## ADDED Requirements

### Requirement: Main-thread default execution
Graph entry points (events, scheduled fires, subgraph invocations) SHALL execute on the Mindustry main thread by default; normal visual nodes and their generated code SHALL run synchronously there unless explicitly marked otherwise.

#### Scenario: Event handler runs on main thread
- **WHEN** a PlayerJoin event triggers an enabled graph
- **THEN** the generated entry method executes on the main thread within that tick's dispatch

### Requirement: Multiple graphs execute concurrently
The engine SHALL load and run many graphs at the same time from a single shared engine instance: independent execution contexts, independent failure domains (an exception or budget kill in one graph MUST NOT affect others), and shared IO/scheduler infrastructure without one-thread-per-graph.

#### Scenario: Independent failure domains
- **WHEN** three graphs are enabled and one throws an unhandled error every trigger
- **THEN** the other two continue executing normally across subsequent triggers

#### Scenario: Concurrent triggers interleave safely
- **WHEN** two different graphs fire on the same event in the same tick
- **THEN** both executions complete with isolated variables and correct node attribution

### Requirement: Runtime lifecycle without restarts
Graphs SHALL be added, updated, enabled, disabled, and removed at runtime through the API with immediate effect and no Mindustry/plugin restart: add compiles-and-loads lazily on first enable/use, update performs atomic generational swap, remove detaches event bridges, cancels live executions deterministically, and releases the compiled binding.

#### Scenario: Add graph while server runs
- **WHEN** a new graph document is posted and enabled during live traffic
- **THEN** its events begin firing within bounded time without any restart step

#### Scenario: Remove graph mid-flight
- **WHEN** an enabled graph with executions suspended in delays is removed
- **THEN** those executions resolve as CANCELLED, pending timers detach, and no further events reach the removed graph

### Requirement: Declared execution requirements enforced
Functions SHALL declare one of `MAIN_THREAD`, `ASYNC`, `PURE`, `READ_ONLY`, `UNSAFE`; the compiler SHALL insert automatic main-thread hops when MAIN_THREAD functions are reached from async segments and MAY exploit PURE/READ_ONLY for safe deduplication.

#### Scenario: Async segment touches main-thread API safely
- **WHEN** a continuation resumed after Await calls `sendMessage` (MAIN_THREAD)
- **THEN** generated code hops the call onto the main thread rather than executing it on the IO thread

### Requirement: Execution lifecycle and identity
Every execution SHALL carry a unique execution id and progress through states PENDING → RUNNING → COMPLETED | FAILED | CANCELLED, with SUSPENDED entered while delayed/awaiting; state transitions SHALL be observable by the debugging subsystem.

#### Scenario: Lifecycle observable
- **WHEN** an execution suspends at Delay then resumes and completes
- **THEN** observers see RUNNING → SUSPENDED → RUNNING → COMPLETED with consistent ids

### Requirement: Cooperative cancellation
Executions SHALL expose a cancellation token checked at node boundaries and before any resume; disabling/removing a graph, server shutdown, debugger stop, or parent cancellation MUST propagate to child executions and pending resumes.

#### Scenario: Disable cancels suspended work
- **WHEN** a graph is disabled while ten executions sit in delays
- **THEN** all ten resume as CANCELLED without executing further nodes

### Requirement: Structured runtime errors
Runtime failures SHALL be reported as structured objects containing graph id/revision, node id, function id where applicable, execution id, error type, message, and stack trace mapped through source maps; unhandled errors SHALL be logged once (not per-node spam) and MUST NOT kill the server thread nor affect sibling graphs.

#### Scenario: Failing node isolates blame
- **WHEN** a registered function throws inside a graph
- **THEN** the error record names that function and its calling node, and subsequent independent executions continue normally

### Requirement: Execution budgets
Generated code SHALL enforce per-execution operation budgets injected at loop back-edges and iteration steps; exceeding budget SHALL fail the execution with a budget-exceeded error attributed to the responsible node, protecting the main thread from runaway loops as a stability guarantee.

#### Scenario: Infinite loop contained
- **WHEN** a Loop node is wired to repeat forever over a constant condition
- **THEN** the execution fails with GraphBudgetExceeded naming that loop node within bounded time

### Requirement: Many concurrent executions without thread-per-graph
The engine SHALL support many concurrently live executions (including suspended ones) using only the main thread plus shared IO pools; live execution count SHALL NOT map linearly to OS threads.

#### Scenario: Suspended load stays flat on threads
- **WHEN** 1,000 executions are triggered and each suspends in a Delay
- **THEN** all 1,000 remain live while the process thread count is unchanged from baseline plus IO pools, and all resume correctly when their delays expire
