# virtual-thread-runtime Specification

## Purpose
TBD - created by archiving change reduce-threads-and-context-switches. Update Purpose after archive.
## Requirements
### Requirement: Java 21 Virtual Thread Execution
The `server` application SHALL run on Java 21 and utilize `Executors.newVirtualThreadPerTaskExecutor()` for `Const.executorService`. All asynchronous and blocking server-side operations, including Docker container log streaming and translation requests, SHALL execute on virtual threads.

#### Scenario: Docker log streaming on virtual threads
- **WHEN** a server container starts and log streaming is attached
- **THEN** the log stream reader task runs on a virtual thread and does not consume a dedicated platform OS thread

#### Scenario: Translation and RPC async tasks on virtual threads
- **WHEN** an async RPC handler or translation API call is dispatched
- **THEN** it executes on a virtual thread without bounding or blocking platform threads

### Requirement: Consolidated Server Scheduler
The `server` application SHALL provide a shared `ScheduledExecutorService` for periodic housekeeping tasks, replacing independent single-thread schedulers in `DockerNodeManager`, `GatewayService`, `ServerService`, and `TranslationService`.

#### Scenario: Single scheduler executes multiple service tasks
- **WHEN** container listing, client sweeps, cache purges, and server reconciliation run
- **THEN** all scheduled tasks are triggered from the shared server scheduler

### Requirement: Gateway Client Lifecycle and RPC Channel Teardown
`GatewayService` SHALL supply the shared server scheduler to each `GatewayClient`'s `RpcChannel` and SHALL call `shutdown()` on the `RpcChannel` when a client is removed or terminated.

#### Scenario: Disconnected client releases RPC resources
- **WHEN** a server container terminates or is removed by `GatewayService.sweep()`
- **THEN** its `GatewayClient` RPC channel is shut down and all pending timer resources are released

### Requirement: Jetty Thread Pool Right-Sizing
The `server` application SHALL configure Javalin's underlying Jetty thread pool with a bounded pool (maximum 32 threads) to prevent excessive thread allocation.

#### Scenario: Jetty server startup
- **WHEN** the Javalin server starts
- **THEN** Jetty initializes with the configured bounded thread pool instead of the default 200+ thread limit

