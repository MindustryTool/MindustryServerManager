## Why

Server owners currently need Java development skills to add any custom plugin logic. A visual flow-graph system (Unreal-Blueprint-style, optimized for Mindustry server plugins) lets them compose gameplay/admin logic visually while advanced users drop into raw code — without sacrificing startup time, main-thread safety, or performance.

Deployment context: the plugin already runs inside an isolated VM, so no in-process security sandboxing is required; the web frontend lives in a separate repository (backend contracts only here).

## What Changes

- Add a **function/type/property/event registry** where Mindustry and plugin APIs are registered as *metadata* (stable IDs, signatures, categories, overloads, generics, nullability, thread requirements) — not as 1,000+ node classes. Registered via build-time annotation processing, programmatic APIs, and generated component registration; exposed through a queryable API.
- Add a small set of **generic node types** (Event, Call, Get/Set Property, Construct, Cast, If, Switch, Sequence, Loop, ForEach, Get/Set Variable, Return, Schedule, Delay, Await, Parallel, Log, Try/Catch/Finally, Throw, Retry/Timeout, HTTP GET/POST/PUT/DELETE, DB Query/Insert/Update/Delete/Transaction, Code) whose ports render dynamically from registry metadata.
- Add a **versioned logical graph format** (nodes/ports/edges/values/variables) kept separate from ReactFlow editor state; documents persist as JSON rows in SQLite (the authoritative store).
- Add a **compiler pipeline**: graph JSON → validation → type check → thread check → IR → generated Java source → JVM compilation → cached executable. Direct generated calls at runtime, no per-call reflection; content-addressed compile cache with incremental recompilation.
- Add a **main-thread-first execution engine** that runs many graphs concurrently: graphs can be added, updated, enabled, disabled, and removed at runtime without restarting the server/plugin, with atomic generational swaps.
- Explicit async support: Schedule (one-shot/recurring/tick-based, cancellable), Delay (suspend/resume on the tick scheduler, never blocking), Await (futures resume on main thread), and async-by-default HTTP nodes plus async DB nodes over the existing ORM.
- Add a **Code node**: arbitrary Java-like bodies compiled natively into the graph against the full classpath (single trust domain — VM isolation assumed); loop budgets retained purely for main-thread stability.
- Add **typed events**, **variable scopes**, **subgraph functions**, and structured error handling with source maps back to exact nodes.
- Add a **debugging layer**: node states, breakpoints, step/resume, inspection, tracing over SSE — plus file-backed per-flow state snapshots gated by a `Core.settings` flag.
- Define backend **API contracts** hosted exclusively by the `server` module (REST + SSE only; the plugin binds no ports), forwarded to the plugin over the existing gateway WS RPC.

## Capabilities

### New Capabilities
- `graph-function-registry`: Metadata-driven registry of functions, properties, events, constructors; discovery/registration mechanisms; lazy initialization; discovery API contract.
- `graph-type-system`: Static graph type system (primitives, Mindustry types, generics, Optional/Future, nullability), inference, conversions, casts, connection-compatibility rejection.
- `logical-graph-format`: Versioned logical graph JSON schema (nodes, ports, edges, values, variables, metadata), editor-state separation, schema validation, DB-backed storage assumption.
- `graph-compilation`: Compiler pipeline from graph JSON through validation, IR, optimization, Java code generation, in-memory/batched compilation, content-addressed caching, incremental recompilation and invalidation, atomic generational swap.
- `graph-execution`: Main-thread-default engine running many graphs concurrently; execution requirements (`MAIN_THREAD|ASYNC|PURE|READ_ONLY|UNSAFE`); lifecycle states; cooperative cancellation; runtime add/update/enable/disable/remove without restarts; budgets; structured errors.
- `graph-scheduling`: Schedule (one-shot/repeating/tick-based, handle + cancel) and Delay (non-blocking suspend/resume) semantics on the Mindustry update loop; Await continuation resume-on-main-thread.
- `http-nodes`: Asynchronous Network GET/POST/PUT/DELETE returning a common `HttpResponse`, with timeout, size limits, redirect policy, pooling, rate limiting, cancellation.
- `database-nodes`: Asynchronous parameterized Query/Insert/Update/Delete/Transaction nodes that never block the main thread, integrating the existing SQLite ORM.
- `code-node`: Arbitrary Java-like code compiled natively into the graph against the full classpath with stability budgets and node-attributed errors (no in-process sandbox — VM isolation).
- `graph-subgraphs`: Reusable graph-defined functions (parameters, returns, local variables, recursion rules, async support, hash versioning) invoked through the standard Call node.
- `graph-debugging`: Execution states, breakpoints, step/resume/cancel, value inspection, tracing/timing, SSE telemetry, source-map fidelity, and file-backed flow snapshots behind a `Core.settings` flag.
- `graph-api`: Server-module-exclusive REST/SSE surface — registry discovery, graph CRUD over SQLite, lifecycle actions with immediate runtime effect, SSE debug/lifecycle streams; plugin hosts no HTTP.

### Modified Capabilities
<!-- None: existing specs' requirements are unchanged. The new subsystem integrates with existing Registry/@Lazy/@Schedule conventions and the server-module gateway without altering their specified behavior. -->

## Impact

- **New code**: new Gradle module `graph` (engine core: format, type checker, IR, codegen, cache, runtime contracts — Jackson only) plus `plugin/graph/**` (Mindustry bindings, registry impl, engine wiring, HTTP/DB nodes, snapshot writer); new annotations in `annotation` module (`@GraphFunction`, `@GraphEvent`, `@GraphProperty`, `@GraphConstructor`) processed into a build-time registry index; new DTOs in `dto`.
- **Modified code**: `Control.java` init/unload register one lazy bootstrap; `Tasks.java` executors reused; `core/Scheduler` gains cancellable handles; ORM gains graph-document tables (per `table-creation` conventions).
- **Build**: Jackson (already present), embedded Java compiler fallback (ECJ) for JRE-only hosts. No bytecode-manipulation library needed (no verification stage).
- **server module**: new REST routes under `/api/v2/graph/**` and SSE streams, proxied to the plugin via `ApiGateway`; JWT-authenticated like existing routes.
- **External web app** (separate repo): consumes the documented REST/SSE contracts; out of scope here.
- **Performance-sensitive surfaces**: startup stays fast (lazy everything, persistent compile cache); main thread never blocks on IO or compilation; snapshot writing is off-thread and flag-gated.
