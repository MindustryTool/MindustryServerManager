## ADDED Requirements

### Requirement: Node execution states
The debugger SHALL expose per-node states PENDING, RUNNING, COMPLETED, FAILED, SUSPENDED, CANCELLED streamed live while attached; detached graphs incur no meaningful overhead.

#### Scenario: States observed during run
- **WHEN** an enabled-for-debug execution passes through If branches and a Delay
- **THEN** subscribers observe transitions including SUSPENDED during the delay and COMPLETED at exit

### Requirement: SSE debug telemetry stream
Debug telemetry (state transitions, traces, inspection payloads) SHALL stream from the server module to clients via Server-Sent Events (`text/event-stream`); client commands (attach, breakpoints, step, resume, cancel) SHALL be plain REST POSTs. WebSocket SHALL NOT be used for client-facing graph streams.

#### Scenario: Attach via REST, receive via SSE
- **WHEN** a client POSTs an attach request then subscribes to the graph's debug SSE endpoint
- **THEN** subsequent node state transitions arrive as SSE events until detach

### Requirement: Breakpoints and stepping
Attached debuggers SHALL set node breakpoints via REST, pause executions before a breakpointed node, then step (node-by-node), resume, or cancel; pausing MUST NOT block unrelated executions or server ticks beyond policy limits.

#### Scenario: Step through branch
- **WHEN** a breakpoint hits on an If node and the user steps three times
- **THEN** exactly the next three nodes execute sequentially under debugger control

### Requirement: Value inspection
At breakpoints and on completion/failure the debugger SHALL provide per-node input/output snapshots delivered over the debug stream.

#### Scenario: Inspect loop iteration values
- **WHEN** paused inside a ForEach body
- **THEN** the inspector data shows current element and accumulated variables for that iteration

### Requirement: Source-map fidelity
Debug events, errors, and timing data SHALL reference node ids resolved through retained source maps such that any client can highlight the exact node for a generated-code location.

#### Scenario: Stack frame highlights node
- **WHEN** an exception originates several frames deep in generated code
- **THEN** the reported location resolves to the originating graph node id

### Requirement: Execution tracing and timing
The debugger SHALL record per-execution traces (node enter/exit order) and per-node timing histograms queryable after runs for performance analysis.

#### Scenario: Hotspot identified
- **WHEN** a repeated graph shows one node dominating duration
- **THEN** the timing data ranks that node first with sample counts

### Requirement: File-backed flow state snapshots behind a Core.settings flag
When a dedicated boolean flag in `Core.settings` is enabled, each live flow (execution) SHALL persist state snapshots to a snapshot file — written on SUSPENDED/RESUMED transitions and at configurable node boundaries — capturing graph id/generation, execution id, current node, continuation state, and variable values as JSON lines; writing MUST occur off the main thread; snapshots MUST stop when the flag is disabled.

#### Scenario: Snapshots written when flag enabled
- **WHEN** `Core.settings` contains the enabled snapshot flag and an execution suspends on Delay
- **THEN** a JSON-line snapshot describing the suspended state appears in the snapshot file without blocking the tick

#### Scenario: No snapshots when flag disabled
- **WHEN** the flag is absent or false
- **THEN** no snapshot files are written and per-execution overhead is unchanged
