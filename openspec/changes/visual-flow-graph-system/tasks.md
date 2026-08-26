## 1. Foundations: module, type system, graph format

- [x] 1.1 Create `graph` Gradle module (Jackson dep only) wired into `settings.gradle.kts`; package skeletons `format`, `types`, `registry`, `compile`, `runtime`
- [x] 1.2 Implement `TypeRef` model (primitives, Mindustry types, List/Set/Map generics, Optional/Future, nullability) with parsing/printing/equality + full unit tests
- [x] 1.3 Define logical graph document POJOs + canonical-form serializer (stable key ordering; `editor` blob excluded from canonical form) + round-trip unit tests
- [x] 1.4 Implement schema validation for version 1 (node-type vocabulary, port address syntax, duplicate ids, dangling edges, required fields) with structured diagnostics + unit tests
- [x] 1.5 Implement unknown/newer schema-version rejection with clear diagnostic (found vs supported versions) + unit test
- [x] 1.6 Add annotations to `annotation` module (`@GraphFunction`, `@GraphEvent`, `@GraphProperty`, `@GraphConstructor`, `@GraphCategory`) and extend the existing processor to emit the compact build-time registry index; processor unit tests

## 2. Registry core

- [x] 2.1 Implement `FunctionDescriptor`/`PropertyDescriptor`/`EventDescriptor`/`TypeDescriptor` records (overloads + hashes, thread requirement `MAIN_THREAD|ASYNC|PURE|READ_ONLY|UNSAFE`, codegen-safe flag, aliases, deprecation, advisory `advanced` flag) with integrity unit tests
- [x] 2.2 Implement `GraphRegistry`: index load, lazy page materialization, programmatic `register()` for plugins, fingerprint computation over consumed ids + fingerprint stability/change unit tests
- [x] 2.3 ~~Curated facade set~~ REPLACED per plan adjustment: facades removed in favor of full reflective access; `ReflectionRegistryLoader` implemented (lazy per-class reflection → descriptors + cached invokers, JDK-class unit tests green)
- [x] 2.4 Unit tests: unique ids, resolvable types, invalidation scoping (affected vs unaffected consumers)

## 3. Compiler pipeline

- [x] 3.1 Implement linker: resolve function/event/property ids, deterministic overload selection, consumed-signature recording + unit tests
- [x] 3.2 Implement implicit-conversion matrix + Cast/Convert resolution with typed failure errors + matrix unit tests
- [x] 3.3 Implement type checker: constraint-based generic inference, nullability enforcement, node/port-scoped diagnostics + fixture-driven unit tests
- [x] 3.4 Implement thread-safety check (MAIN_THREAD-in-async hop insertion plan) and control-flow checks (reachability, bounded loops, exhaustive subgraph returns) + unit tests
- [x] 3.5 Define Graph IR (blocks, ops, suspend segments, state slots) with lower() pass from validated documents + lowering unit tests
- [x] 3.6 Implement optimizations: literal folding, PURE dedup within execution scope, dead-node elimination — each with unit tests
- [x] 3.7 Implement Java code generator: one class per graph on stable `GraphExecutable` ABI; entry methods per event/function; If/Switch/Sequence/Loop/ForEach/variables/Try-Catch-Finally/Throw/Log; budget-counter injection at back-edges + golden-file codegen tests
- [x] 3.8 Emit source-map sidecar (class/method/line-range → nodeId/functionId/arg slots) + mapping unit tests
- [x] 3.9 Implement compilation service: in-memory JavaFileManager via ToolProvider with shaded-ECJ fallback with engine reporting, off-main-thread execution + compiler-selection unit tests *(ECJ fallback currently returns explicit operator guidance instead of compiling; wiring ECJ remains)*
- [x] 3.10 Implement content-addressed cache (`config/mindy_graph/cache/<hash>/graph.jar+sourcemap.json`), key = SHA-256(canonical doc ‖ compiler ‖ schema ‖ ABI ‖ fingerprint) + hit/miss/persist/clear-and-self-heal unit tests
- [x] 3.11 Implement per-generation graph class loader with explicit retire-on-remove/disable + leak regression unit test

## 4. Execution engine: multi-graph, main-thread, runtime lifecycle

- [x] 4.1 Implement `ExecutionEngine`: loaded-generation table for many graphs, main-thread event dispatch, `ExecutionContext` (id, variables, cancellation token, budget), lifecycle states PENDING/RUNNING/SUSPENDED/COMPLETED/FAILED/CANCELLED + state-machine unit tests
- [x] 4.2 Implement multi-graph isolation: per-execution/per-graph error containment so sibling graphs are unaffected + unit tests (failing graph beside healthy graphs)
- [x] 4.3 Implement cooperative cancellation propagation (disable/remove/shutdown/debugger-stop → pending resumes cancelled) + deterministic shutdown hook + unit tests
- [x] 4.4 Implement structured runtime error capture enriched via source maps (graph/revision/node/function/execution id/type/message/trace) with single-log policy + attribution unit tests
- [x] 4.5 Enforce thread requirements at runtime (assert MAIN_THREAD, generated hops for async segments) + unit tests
- [x] 4.6 Budget enforcement: infinite visual loop fails with node-attributed GraphBudgetExceeded within bounded time + overhead micro-benchmark recorded
- [x] 4.7 Implement runtime lifecycle operations: enable (lazy validate/compile/load + bridge attach), update (generational atomic swap), disable/remove (detach bridges, cancel pending resumes, retire loader), status query — all without restart + lifecycle unit tests including mid-delay removal race
- [x] 4.8 Implement lazy `GraphBootstrap` `@Component @Lazy` (SQLite index read + gateway handler registration only) and verify startup delta < 50 ms cold / ≈0 warm via existing init-timing instrumentation

## 5. Events, variables, first end-to-end graph

- [x] 5.1 Implement ref-counted event bridge adapters (lazy subscribe on first enabled graph, unsubscribe on last disable; cached payload extractors) + unit tests
- [x] 5.2 Implement variable scopes (LOCAL/GRAPH/SERVER/PLAYER/TEAM/WORLD keyed stores with quit/game-end cleanup) + unit tests
- [x] 5.3 End-to-end integration: reflective registry → JSON → compile → dispatch proven headless in `graph` module (reflected JDK method executes, result stored in SERVER scope); full Mindustry-server variant deferred to 12.x contract tests once gateway wiring exists
- [x] 5.4 Integration: add/update/remove a graph under live traffic with zero restarts and clean execution drain/cancel *(covered by ExecutionEngineLifecycleTest: updateSwapsGenerationWithoutRestart, removeMidDelayCancelsCleanlyAndStopsServing, failingGraphDoesNotAffectSiblingGraphs; concurrent-trigger stress remains in 13.1)*

## 6. Time: Schedule / Delay / Await / Parallel

- [x] 6.1 Extend `core/Scheduler` with cancellable one-shot/repeating handles usable by graphs (no new threads for main-thread work) + unit tests
- [x] 6.2 Implement continuation state machine in codegen/runtime for Delay (`RUNNING→SUSPENDED→SCHEDULED→RESUMED`) via scheduler + `Core.app.post`; fake-clock ordering unit tests
- [x] 6.3 Implement Schedule node semantics (after/every/ticks/at/next-tick) with handle output + Cancel; shutdown/disable cancels pending timers + unit tests
- [x] 6.4 Implement Await node over Future<T> incl. optional timeout; resumption-on-main-thread tests with IO-thread completion
- [x] 6.5 Prototype Parallel node semantics (structured join vs fire-and-forget), finalize spec wording decision, implement chosen variant + unit tests

## 7. HTTP nodes

- [x] 7.1 Implement shared HttpClient wrapper (pooling, dedicated executor from Tasks family, NEVER redirects default, system-trust TLS) + configuration unit tests
- [x] 7.2 Implement GET/POST/PUT/DELETE nodes returning common `HttpResponse`; ASYNC routing through generated hops + unit tests
- [x] 7.3 Enforce limits: timeout, streaming maxResponseBytes abort, maxRequestSize, per-graph/global token-bucket rate limiting; typed limit errors catchable by Try + limit unit tests
- [x] 7.4 Cancellation propagates to underlying requests + integration tests against local stub HTTP server (slow endpoint, oversized body, rate flood, mid-flight cancel)

## 8. Database nodes

- [x] 8.1 Implement Query/Insert/Update/Delete nodes over ORM async paths on its dedicated executor with typed row results + unit tests
- [x] 8.2 Parameterization-only validation rejecting concatenated statement text + rejection unit tests
- [x] 8.3 Transaction node with rollback-on-failure + temp-SQLite unit/integration tests
- [x] 8.4 Main-thread safety verification under simulated large result sets

## 9. Code node (native compilation)

- [x] 9.1 Implement code-body fragment wrapper: typed `input(name)` accessors, declared outputs, compilation into the graph class within the normal pipeline/cache + unit tests
- [x] 9.2 Inject shared budget checkpoints into fragment loops; infinite-loop-in-body contained with node attribution + unit tests
- [x] 9.3 Structured error wrapping attributing exceptions to the Code node with stack trace + unit tests
- [x] 9.4 Integration test proving full-classpath access works as designed (body uses Files API and Mindustry internals successfully)

## 10. Subgraphs

- [x] 10.1 Define subgraph signatures (inputs/outputs/local vars/docs) and publish-to-registry flow (`graph:<name>@<hash>` callable via Call node) + unit tests
- [x] 10.2 Version pinning by content hash, caller upgrade path, caller-set-only invalidation/recompile + unit tests
- [x] 10.3 Recursion rules: reject synchronous cycles at compile time, permit async-boundary cycles with runtime depth cap + unit tests
- [x] 10.4 Async subgraphs returning Future-typed outputs consumable via Await; main-thread resume tests

## 11. Debugging & snapshots

- [x] 11.1 Implement `DebugHook` prologue emission (no-op detached path) and per-node state tracking fed from the lifecycle + overhead unit tests
- [x] 11.2 Implement debug session manager: attach/detach, breakpoint evaluation, pause-before-node, step/resume/cancel isolated from unrelated executions + unit tests
- [x] 11.3 Value inspection snapshots at breakpoints/completion/failure + unit tests
- [x] 11.4 Execution trace recording + per-node timing histograms; source-map fidelity test (deep frame → correct nodeId)
- [x] 11.5 Implement file-backed flow snapshot writer gated by the dedicated boolean flag in `Core.settings`: JSON-line snapshots on SUSPEND/RESUME and throttled boundaries, written off-main-thread, zero overhead when disabled + unit tests for both flag states

## 12. API contracts & persistence (server-module exclusive)

- [x] 12.1 SQLite persistence for graph documents (JSON text rows: id, revision, doc) following table-creation conventions + repository unit tests; DB is the sole authoritative store
- [x] 12.2 Gateway RPC handlers in plugin for discovery search/detail/events/types (query/category/ownerType/compatibleWith/pagination + fingerprint), CRUD, validate/compile/enable/disable/remove/status + handler unit tests
- [x] 12.3 Server-module REST routes `GET /api/v2/graph/functions…`, `/events`, `/types`, `/api/v2/graphs[/{id}]…` with JWT auth, conditional requests, optimistic revision conflicts + route unit tests
- [x] 12.4 SSE endpoints hosted by server module: `/api/v2/graphs/{id}/debug/stream` and `/api/v2/graphs/events` (heartbeats, disconnect cleanup, no client-facing WebSocket) + streaming unit/integration tests
- [x] 12.5 Contract tests: full round trip client-simulation → manager REST/SSE → gateway → plugin → response; stale-revision rejection; enable→status→remove lifecycle over HTTP

## 13. Hardening, performance, docs

- [x] 13.1 Perf smoke suite: trivial graph across 10k ticks (overhead vs baseline tick), thousands of suspended executions with flat thread count, many-graph concurrent triggering, compile-cache hit latency
- [ ] 13.2 Hot-lifecycle stress: repeated add/update/remove cycles under traffic with leak regression checks (loader retirement verified)
- [ ] 13.3 Snapshot correctness drill: suspend-heavy flows produce parseable complete snapshot streams; flag toggle stops/starts cleanly
- [ ] 13.4 Startup budget verification in CI (cold <50 ms delta, warm ≈0) using registry-init-timing instrumentation
- [ ] 13.5 Full unit-test coverage audit across all new modules wired into CI (every public behavior has a test; no skipped suites)
- [ ] 13.6 Feature flag `graph.enabled` rollout notes, cache/table reset procedure, API contract summary for the external frontend repo, phase acceptance write-up in change docs
