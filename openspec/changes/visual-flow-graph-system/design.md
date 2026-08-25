## Context

This repo contains the Mindustry plugin (`plugin`, entry `plugin.Control`), the annotation/processor subproject (`annotation`, generates `ComponentRegistry`), shared `dto`, and the standalone manager (`server`, Javalin :8088, JWT auth, SSE + WS `/gateway`). The web frontend (React + ReactFlow) lives in a separate repo and is out of scope here — this change delivers backend contracts only. The plugin connects **outbound** to the manager via the WS gateway (`plugin/gateway/ApiGateway.java`). Existing infrastructure to reuse: `core/Registry` DI container with `@Component/@Lazy/@Destroy`, `core/Scheduler` (tick-driven `@Schedule`), `Tasks.java` IO/CPU executors, the SQLite ORM with sync+async parity, and the `WsMessage` request/response gateway protocol. Java 17, Mindustry v159.2 (Arc). No graph/visual-programming code exists yet.

Constraints that shape this design:
- Mindustry exposes 1,000+ useful methods; anything that scales per-method (node classes, frontend hardcoding, eager scanning) is disqualified.
- Plugin/server startup time matters; the Mindustry main thread must never block.
- **The plugin already runs inside an isolated VM** — host security is delegated to the VM. No in-process sandbox machinery (no bytecode verification, stub APIs, restricted classloaders, SecurityManager).
- **Only the `server` module may serve HTTP/SSE**; the plugin module binds no ports and reaches clients exclusively through the existing gateway.
- Realtime client-facing telemetry uses **SSE, not WebSocket** (the internal plugin⇄manager gateway remains WS as today).

## Goals / Non-Goals

**Goals:**
- Visual programming with a *small closed set* of generic node types; all Mindustry/plugin functionality arrives as **registry metadata** driving dynamic ports.
- Compile graphs to plain Java (direct calls, JIT-friendly); no per-execution reflection on hot paths; persistent compile cache; incremental recompilation.
- Main-thread-default execution with explicit, safe async (Schedule/Delay/Await/HTTP/DB) that always resumes on the main thread.
- One engine running **many graphs concurrently**, with **runtime add/update/enable/disable/remove** and zero restarts.
- Full observability: errors map back to exact nodes via source maps; per-flow **state snapshots to file** behind a `Core.settings` flag; SSE debug streams.
- Lazy everything at startup; heavy subsystems initialize on first use.
- SQLite is the **sole graph store** (JSON documents in rows); complete unit-test coverage for every component.

**Non-Goals:**
- No in-process sandboxing or capability enforcement — the VM is the trust boundary; Code node bodies run with full plugin privileges.
- No frontend implementation in this repo (contracts only).
- No schema migrations: documents at unknown/newer schema versions are rejected with a clear diagnostic.
- No interpreter fallback as primary execution path.
- No distributed/multi-server graph synchronization.
- No persistent variable storage in v1 (server-scope variables are in-memory).

## Risk Analysis (drives the decisions below)

1. **Dynamic Java compilation problems.** `ToolProvider.getSystemJavaCompiler()` returns null on JRE-only hosts; compiling to disk is slow/racy; classloader leaks accumulate across recompiles. → **Resolution:** in-memory JavaFileManager; ECJ shaded as fallback compiler; compiled classes loaded via a dedicated loader per graph generation, explicitly retired on remove/disable; compilation happens off-thread.
2. **Main-thread/async interaction.** Callbacks arriving on IO threads touching Mindustry state corrupt it; blocking the main thread on futures freezes the server; cancellation races leave dangling continuations. → **Resolution:** every async continuation is re-posted to the main thread (Arc `Core.app.post`) by generated trampolines; nothing ever `.join()`s on the main thread; executions carry a cancellation token checked at node boundaries and before resume; shutdown hook cancels all live executions deterministically.
3. **Stability of arbitrary user code (visual loops and Code bodies).** Infinite loops or runaway iteration freeze the main thread even without an adversary. → **Resolution:** compiler-injected operation budgets at loop back-edges/iteration steps fail the execution fast; framed purely as stability, not security.
4. **Startup/lazy-loading concerns.** Scanning Mindustry classes or compiling graphs at boot destroys startup time. → **Resolution:** build-time annotation processing produces a compact registry index (no runtime scanning); startup reads a few SQLite rows + registers one `@Lazy` bootstrap component; metadata pages, graph loading, compilation, HTTP client, snapshot writer, debug infra are all first-use initialized; persistent compile cache makes warm restarts O(load classes).
5. **Hot lifecycle races.** Removing/updating a graph while executions are suspended mid-delay can hit dead bindings. → **Resolution:** generational bindings (graph id + generation counter); removal marks the generation dead, cancels/detaches pending resumes deterministically, then releases the loader; new triggers bind to the new generation only after a full atomic swap.
6. **Metadata drift.** Facade signatures rot as Mindustry updates; stale caches serve wrong signatures. → **Resolution:** registry fingerprint embedded in every compile-cache key; mismatch ⇒ revalidate/recompile; facade cross-check tests in CI.

## Architecture Overview

```
Editor/client (separate frontend repo)
   ↕ REST + SSE only (server module :8088 ⇄ gateway WS RPC ⇄ plugin)
Logical Graph JSON (versioned; stored as rows in SQLite)
   ↓ Parser / Schema validation
Type check · Thread check · Control-flow check
   ↓ Graph IR
Java source generation (+ source maps)
   ↓ In-memory javac/ECJ
Content-addressed cache → Loaded generation
   ↓
Execution Engine (many graphs; MAIN THREAD default; Arc scheduler for time; Tasks pool for IO;
optional file-backed flow snapshots when Core.settings flag enabled)
```

Module layout:
- **New Gradle module `graph`** — engine core: format, parser, type checker, IR, codegen, cache, runtime contracts. Depends only on Jackson. Headless-testable without Mindustry.
- **`plugin/src/main/java/plugin/graph/**`** — Mindustry bindings: registry implementation, facade functions/events/properties, execution engine wiring (main thread, Scheduler), HTTP/DB node implementations, snapshot writer, gateway integration, SQLite persistence.
- **`annotation`** — new annotations (`@GraphFunction`, `@GraphEvent`, `@GraphProperty`, `@GraphConstructor`) processed at build time into the registry index.
- **`dto`** — metadata/graph/debug message records shared with the manager.
- **`server`** — REST routes under `/api/v2/graph/**` plus SSE streams, proxying to the plugin over the existing gateway protocol. The plugin never hosts HTTP.

Trust model: single domain. The VM isolates the host; graphs and Code bodies are trusted plugin-grade code. The only "guardrails" retained are stability mechanisms (budgets, size limits on IO) — not security boundaries.

## Decisions

### D1. Functions are data; generic node set
~20 generic node types (Event, Call, Get/Set Property, Construct, Cast/Convert, If, Switch, Sequence, Loop, ForEach, Get/Set Variable, Return, Schedule, Delay, Await, Parallel, Log, Try/Catch/Finally, Throw, Retry/Timeout, HTTP×4, DB×5, Code). Every domain operation is `(functionId, resolved-overload, arguments)` inside a Call node. *Alternative considered* (per-function node classes) rejected: unmaintainable at 1,000+ functions.

### D2. Registry metadata model + population channels
`FunctionDescriptor`: stable id (`mindustry.player.sendMessage`), display name, description, category, owner `TypeRef`, ordered params (name, `TypeRef`, nullability), return `TypeRef` + nullability, overloads with stable hashes, generics templates, throws-flag, thread requirement (`MAIN_THREAD|ASYNC|PURE|READ_ONLY|UNSAFE`), codegen-safe flag, aliases, deprecation, since-version, advisory `advanced` display flag. Population: (1) curated annotated facade classes processed at build time into a compact index (an offline Gradle task may scaffold facades via build-time reflection — humans curate); (2) programmatic `GraphRegistry.register(...)` for plugins; (3) generated registration from annotated component methods. No runtime classpath scanning.

### D3. Invocation strategy
Generated code calls facade methods directly. Dynamically registered functions get an `Invoker` bound once at load time (lambdas/MethodHandles constant-folded), never per execution.

### D4. Type system
`TypeRef` tree: primitives (`String,Int,Long,Float,Double,Boolean,Byte`), registered Mindustry types (`Player,Unit,Building,Tile,Team,Block,Item,Liquid,Bullet,World,GameState,Connection,…` extensible), collections (`List<T>,Set<T>,Map<K,V>`), wrappers (`Optional<T>,Future<T>`), nullability flags. Implicit-conversion matrix (numeric widening, literal-friendly conversions), explicit Cast node, constraint-based inference (ForEach over `List<Player>` binds `T=Player`). Frontend checks are advisory; compiler validation is authoritative.

### D5. Logical graph format
Single JSON document: `version` (schema gate — unknown versions rejected, **no migrations**), `id`, monotonic `revision`, variable declarations, nodes (`id`,`type`,payload), edges (`nodeId.portName` addresses), subgraph references, opaque `editor` blob stripped before canonicalization/compilation. Persisted as JSON text in a SQLite row (authoritative store). Canonical form drives compile-cache keys so editor-only edits never invalidate artifacts.

### D6. Compiler pipeline & IR
Parse → schema-validate → link (resolve ids, pick overload) → type-check (inference/conversions; node-scoped errors) → thread check (MAIN_THREAD ops reached from async segments get inserted hops) → flow check (reachability, bounded loops, exhaustive returns for subgraphs) → lower to IR: block graph `{nodeId, op, inputs, outputs}` where Delay/Await split bodies into continuation segments with explicit state slots. Optimizations: literal folding, PURE dedup within execution scope, dead-node elimination. Output: one Java class per graph implementing the stable `GraphExecutable` ABI; entry methods per event/function; continuation switch for suspendable bodies. Source-map sidecar (`{class, method, line-range → nodeId}`) emitted alongside.

### D7. Compilation & caching
Compile off-main-thread via `ToolProvider`; shaded **ECJ** fallback when absent; clear operator error if both unavailable. In-memory JavaFileManager → byte arrays → persisted to `config/mindy_graph/cache/<hash>/graph.jar + sourcemap.json`. Key = SHA-256(canonical doc ‖ compilerVersion ‖ schemaVersion ‖ abiVersion ‖ registryFingerprint(used ids+overload hashes)). Hits skip compilation across restarts; the cache is disposable derived data (SQLite document is authoritative). Changed graphs recompile alone. Generational classloaders retired on disable/remove (leak regression test included).

### D8. Execution engine (multi-graph, main-thread default)
One engine instance owns the loaded-graph table (`id → Generation{class, sourcemap}`) for arbitrarily many graphs, live executions per graph, and shared scheduler/IO wiring. Events dispatch onto the calling thread (Mindustry main thread for built-in events). `ExecutionContext`: unique id, variables, cancellation token, op budget. Thread requirements enforced at runtime; ASYNC continuations re-posted to main; budgets injected by codegen kill runaway executions with node attribution; failures are isolated per execution and per graph (sibling graphs unaffected).

### D9. Runtime lifecycle (add/remove without restart)
API actions map to engine operations: **enable** = lazily validate/compile/load new generation, attach event bridges (ref-counted); **update** = load next generation fully then atomically swap pointer; in-flight executions finish or cancel per policy on the old generation; **disable/remove** = detach bridges, mark generation dead, cancel pending resumes deterministically, retire loader. All observable immediately through status/lifecycle SSE events; no restart involved.

### D10. Time: Schedule, Delay, Await
Built on the existing tick `Scheduler` extended with cancellable one-shot/repeating handles — **no extra threads for main-thread work**.
- **Schedule node:** `after/every/ticks/at/next-tick`, emits cancellable `ScheduleHandle`.
- **Delay node:** splits execution into continuation; `RUNNING→SUSPENDED→SCHEDULED→RESUMED`; fires resume via `Core.app.post`; never `Thread.sleep`.
- **Await node:** subscribes to `Future<T>`; completion re-posted to main; cancellation detaches listener. Cancellation token checked before every resume.

### D11. HTTP nodes
Shared `HttpClient` (pooling, own executor from Tasks IO family) + per-graph/global token-bucket rate limiter. Streaming `BodyHandler` enforces max response bytes; request-size cap; timeout; redirect policy `NEVER` default; system-trust TLS; cancellation propagates to the request. Common `HttpResponse {status, headers, body, success}`. Thread requirement `ASYNC` ⇒ generated hops route off-main and resume on-main automatically.

### D12. Database nodes
Thin async wrappers over the existing ORM (`Query/Insert/Update/Delete/Transaction`) on its dedicated executor; strictly parameterized binding (validation rejects concatenated statement text — authoring-correctness guard); typed row results; Transaction groups writes with rollback-on-failure.

### D13. Code node (native, unsandboxed)
User body compiles as a generated fragment method inside the graph class against the **full plugin/Mindustry classpath** — same pipeline, cache, versioning as visual nodes. Retained guardrails are stability-only: operation-budget checkpoints identical to visual loops, standard structured error wrapping attributing exceptions to the node. No stub APIs, no verifier, no restricted loaders, no capability checks.

### D14. Governance-lite metadata
Without enforcement needs, dangerous-but-legitimate APIs carry advisory flags only: `advanced` (grouping/warning hints for editors) and `deprecated`. These influence display and search ranking, nothing else.

### D15. Errors, debugging, snapshots, source maps
Errors funnel through a generated catch-all enriched via source maps into `{graphId, revision, nodeId, functionId, executionId, errorType, message, stackTrace}`. Debugger opt-in per graph: cheap `DebugHook.tick(nodeId, state)` prologue per node (no-op when detached); attached mode streams states/breakpoints/steps/inspection/timing over **SSE** from the server module (commands via REST POST). **State snapshots:** when the dedicated boolean in `Core.settings` is set, each flow writes JSON-line snapshots (graph id/generation, execution id, current node, continuation slot, variables) to a snapshot file on SUSPENDED/RESUMED transitions and throttled node boundaries — written via the IO executor, never the main thread; disabled ⇒ zero overhead.

### D16. API contract (server-module-exclusive HTTP/SSE)
Routes hosted by `server` (JWT-authenticated), forwarded over gateway `WsMessage` RPC:
- Discovery: `GET /api/v2/graph/functions?query&category&ownerType&compatibleWith&limit`, `/functions/{id}`, `/events`, `/types` — fingerprint + conditional-request support.
- CRUD: `GET/POST/PUT/DELETE /api/v2/graphs[/{id}]` over SQLite rows with optimistic revision conflict detection.
- Lifecycle: `POST .../validate | /compile | /enable | /disable | /remove`, `GET .../status`.
- SSE: `GET /api/v2/graphs/{id}/debug/stream`, `GET /api/v2/graphs/events` (heartbeats, clean disconnect cleanup). WebSocket is not used client-facing; the internal plugin⇄manager gateway stays WS as today.

### D17. Lazy loading & startup budget
Startup does exactly: register one `@Lazy @Component GraphBootstrap` (installs gateway handlers + reads the small graph index from SQLite). On demand: registry pages, event bridges, compiler service, HTTP client, snapshot writer, debug infra. Warm restarts skip compilation via persistent cache. Measured startup delta target: < 50 ms cold, ≈ 0 ms warm.

### D18. Testing mandate
Every component ships with complete unit tests in the same change (registry, linker, type checker, codegen goldens, cache keys, lifecycle state machines, snapshot writer, SSE proxies). Integration tier boots a headless Mindustry server for end-to-end flows (join→message, hot add/remove under traffic, multi-graph concurrency, warm-cache restart). Escape/security corpora are explicitly out of scope (VM trust model).

## Risks / Trade-offs

- [ECJ shading adds ~3–4 MB to plugin jar] → acceptable; loaded only on first compile.
- [Stub-free Code node means bugs can crash a graph's executions] → structured error isolation per execution/graph; budgets bound blast radius; acceptable because code authors are the server's own operators in a disposable VM.
- [Budget injection adds minor loop overhead] → counter increments are cheap; measured in perf smoke; configurable ceiling.
- [Per-generation classloaders can leak on frequent redeploys] → explicit retire-on-remove/disable + leak regression test.
- [Gateway hop adds latency to editor interactions] → registry index cached at manager with fingerprints; only mutations/debug are realtime-sensitive.
- [SSE lacks native bidirectionality] → commands are simple REST POSTs; fits existing manager SSE patterns.
- [Large design surface invites scope creep] → tasks.md locks phase boundaries; later phases depend on earlier acceptance criteria.

## Migration Plan

Purely additive: no existing spec behavior changes. Rollout follows tasks.md phases behind a single `graph.enabled` config (default off until Phase 3 acceptance). Rollback = disable component + drop `config/mindy_graph/` tables/cache (cache is disposable; graph documents live in SQLite and remain untouched).

## Open Questions

- Exact payload shape for Parallel node cancellation semantics (structured-join vs fire-and-forget branches) — prototype in Phase 6 before finalizing spec wording.
