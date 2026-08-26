package graph.compile;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import graph.registry.GraphRegistry;
import graph.registry.Invoker;
import graph.runtime.CancellationToken;
import graph.runtime.InvocationContext;
import graph.runtime.GraphExecutable;
import graph.runtime.GraphReturnSignal;
import graph.runtime.MainThreadDispatcher;
import graph.runtime.RuntimeServices;
import graph.runtime.VariableStore;

    public final class ExecutionEngine {

    public enum ExecutionState {
        PENDING, RUNNING, SUSPENDED, SCHEDULED, COMPLETED, FAILED, CANCELLED
    }

    public record StructuredError(long executionId, String graphId, int generation,
            String nodeId, String functionId, String errorType,
            String message, List<StackTraceElement> stackTrace) {
    }

    public record Generation(int number, GraphExecutable executable,
            GenerationClassLoader loader, SourceMap sourceMap) {

        /** Executables carry per-execution state; use fresh instances. */
        public GraphExecutable newExecutable() throws Exception {
            return (GraphExecutable) loader
                    .loadMain(executable.getClass().getName())
                    .getDeclaredConstructor().newInstance();
        }
    }

    public static final class LoadedGraph {

        private final String id;
        private final List<Execution> live = new CopyOnWriteArrayList<>();
        private final java.util.List<graph.runtime.ScheduleHandle> timers =
                new CopyOnWriteArrayList<>();
        private volatile Generation current;
        private volatile boolean enabled;

        LoadedGraph(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public Generation generation() {
            return current;
        }

        public int liveExecutions() {
            return live.size();
        }


        int nextGenerationNumber() {
            Generation g = current;
            return g == null ? 1 : g.number() + 1;
        }

        public List<Execution> executions() {
            return List.copyOf(live);
        }
    }

    public static final class Execution {

        private final long id;
        private final LoadedGraph graph;
        private InvocationContext context;
        private final VariableStore variables = new VariableStore();
        private final Map<String, Object> payload;
        private final List<String> trace = new ArrayList<>();
        private final Map<String, Long> nodeVisitCounts = new ConcurrentHashMap<>();
        private final Map<String, Long> nodeTimeNanos = new ConcurrentHashMap<>();
        private final List<Runnable> onCancel = new CopyOnWriteArrayList<>();
        final java.util.concurrent.atomic.AtomicInteger pendingHops =
                new java.util.concurrent.atomic.AtomicInteger();
        private volatile ExecutionState state = ExecutionState.PENDING;
        private volatile Throwable failure;

        Execution(long id, LoadedGraph graph, Map<String, Object> payload,
                long maxOperations) {
            this.id = id;
            this.graph = graph;
            this.payload = payload == null ? Map.of() : payload;
            this.context = new InvocationContext(id, new CancellationToken(),
                    new InvocationContext.Budget(maxOperations));
        }

        public long id() {
            return id;
        }

        public ExecutionState state() {
            return state;
        }

        public LoadedGraph graph() {
            return graph;
        }
        public List<String> trace() { synchronized (trace) { return List.copyOf(trace); } }
        public long nodeVisitCount(String nodeId) {
            return nodeVisitCounts.getOrDefault(nodeId, 0L);
        }
        public long nodeTimeNanos(String nodeId) {
            return nodeTimeNanos.getOrDefault(nodeId, 0L);
        }

        void cancel(String reason) {
            context.cancellation().cancel(reason);
            switch (state) {
                case PENDING, RUNNING, SUSPENDED, SCHEDULED -> state = ExecutionState.CANCELLED;
                default -> {
                }
            }
            for (Runnable hook : onCancel) {
                try {
                    hook.run();
                } catch (RuntimeException ignored) {
                }
            }
            onCancel.clear();
        }
    }

    @FunctionalInterface
    public interface Scheduler {
        Handle schedule(double seconds, Runnable continuation);

        interface Handle {
            void cancel();
        }
    }

    private final MainThreadDispatcher main;
    private final GraphRegistry registry;
    private final Consumer<StructuredError> errorSink;
    private final Consumer<String> logger;
    private final long maxOperationsPerExecution;
    private final boolean enforceMainThread;

    private final Map<String, LoadedGraph> graphs = new ConcurrentHashMap<>();
    private final Map<String, VariableStore> graphStores = new ConcurrentHashMap<>();
    private final ScopedVariables scoped = new ScopedVariables();
    private final AtomicLong executionSeq = new AtomicLong(1);
    private final Map<String, Long> recentSignatures = new ConcurrentHashMap<>();

    private Scheduler scheduler = (seconds, continuation) -> {
        throw new IllegalStateException("No engine scheduler installed");
    };
    private ExecutorService asyncPool = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "graph-async");
        thread.setDaemon(true);
        return thread;
    });

    public ExecutionEngine(MainThreadDispatcher main, GraphRegistry registry,
            Consumer<StructuredError> errorSink, Consumer<String> logger,
            long maxOperationsPerExecution, boolean enforceMainThread) {
        this.main = Objects.requireNonNull(main);
        this.registry = Objects.requireNonNull(registry);
        this.errorSink = Objects.requireNonNull(errorSink);
        this.logger = Objects.requireNonNull(logger);
        this.maxOperationsPerExecution = maxOperationsPerExecution;
        this.enforceMainThread = enforceMainThread;
    }

    public void installScheduler(Scheduler scheduler) {
        this.scheduler = Objects.requireNonNull(scheduler);
    }

    public interface HttpDelegate {
        java.util.concurrent.CompletableFuture<?> execute(String key, String method,
                                                          String url, Map<String, String> headers,
                                                          Map<String, String> query,
                                                          String body);
    }

    private volatile HttpDelegate httpDelegate;

    public void installHttpDelegate(HttpDelegate delegate) {
        this.httpDelegate = Objects.requireNonNull(delegate);
    }

    public interface DbDelegate {
        java.util.concurrent.CompletableFuture<?> query(String key, String sql,
                Map<String, Object> params);

        java.util.concurrent.CompletableFuture<?> update(String key, String kind,
                String table, Map<String, Object> row);
    }

    private volatile DbDelegate dbDelegate;

    public void installDbDelegate(DbDelegate delegate) {
        this.dbDelegate = Objects.requireNonNull(delegate);
    }

    private volatile graph.runtime.DebugHook debugHook;

    public void installDebugHook(graph.runtime.DebugHook hook) {
        this.debugHook = hook;
    }

    public static final class SubgraphEntry {
        final LoadedGraph graph;
        final Generation generation;
        final String hash;

        SubgraphEntry(LoadedGraph graph, Generation generation, String hash) {
            this.graph = graph;
            this.generation = generation;
            this.hash = hash;
        }
    }

    private final java.util.Map<String, SubgraphEntry> subgraphs =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final int MAX_SUBGRAPH_DEPTH = 64;
    private final ThreadLocal<Integer> subgraphDepth =
            ThreadLocal.withInitial(() -> 0);
    private final java.util.Map<String, List<String>> subgraphCalleesByName =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<String> asyncBoundaryNames =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Map<String, String> activeHashByName =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, java.util.Set<String>> callerSetsByKey =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Loads a compiled subgraph and exposes it as callable
     * {@code graph:<name>@<hash>} from within other graphs. Callers must also
     * register a matching placeholder descriptor in the registry so linking
     * succeeds.
     */
    public void publishSubgraph(String name, String hash, String sourceDocId,
            Map<String, byte[]> classes) throws Exception {
        publishSubgraph(name, hash, sourceDocId, classes, List.of(), false);
    }

    /**
     * Full form: declares the callable subgraphs this one invokes (for
     * compile-time synchronous-cycle rejection) and whether its body contains
     * an async boundary (Delay/Await), which permits cycles.
     */
    public synchronized void publishSubgraph(String name, String hash,
            String sourceDocId, Map<String, byte[]> classes,
            List<String> calleeNames, boolean hasAsyncBoundary) throws Exception {
        List<String> previousCallees = subgraphCalleesByName.get(name);
        boolean previousAsync = asyncBoundaryNames.contains(name);
        if (hasAsyncBoundary) {
            asyncBoundaryNames.add(name);
        }
        subgraphCalleesByName.put(name, List.copyOf(calleeNames));
        List<String> cycle = findSyncCycleFrom(name);
        boolean cycleAllowed = cycle == null || cycle.stream()
                .anyMatch(asyncBoundaryNames::contains);
        if (!cycleAllowed) {
            if (previousCallees == null) {
                subgraphCalleesByName.remove(name);
            } else {
                subgraphCalleesByName.put(name, previousCallees);
            }
            if (hasAsyncBoundary && !previousAsync) {
                asyncBoundaryNames.remove(name);
            }
            throw new IllegalArgumentException(
                    "Synchronous recursion cycle rejected: "
                            + String.join(" -> ", cycle));
        }
        String callableId = "graph:" + name + "@" + hash;
        LoadedGraph loaded = graphs.computeIfAbsent(callableId, LoadedGraph::new);
        Generation generation = buildGeneration(sourceDocId,
                loaded.nextGenerationNumber(), classes,
                GraphExecutable.class.getClassLoader(), null);
        loaded.current = generation;
        loaded.enabled = true;
        subgraphs.put(name + "@" + hash, new SubgraphEntry(loaded, generation, hash));
        if (hasAsyncBoundary) {
            asyncBoundaryNames.add(name);
        } else {
            asyncBoundaryNames.remove(name);
        }
        activeHashByName.put(name, hash);
    }

    private List<String> findSyncCycleFrom(String start) {
        for (String first : subgraphCalleesByName.getOrDefault(start, List.of())) {
            List<String> path = new ArrayList<>();
            path.add(start);
            path.add(first);
            List<String> found = pathTo(first, start, new java.util.HashSet<>(), path);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private List<String> pathTo(String from, String target,
            java.util.Set<String> visited, List<String> path) {
        if (from.equals(target)) {
            return path;
        }
        if (!visited.add(from)) {
            return null;
        }
        for (String callee : subgraphCalleesByName.getOrDefault(from, List.of())) {
            path.add(callee);
            List<String> found = pathTo(callee, target, visited, path);
            if (found != null) {
                return found;
            }
            path.remove(path.size() - 1);
        }
        return null;
    }

    /**
     * Publishes a new hash revision of {@code name}, retires the previously
     * active revision, and disables exactly the recorded caller set of the
     * retired hash so they recompile against the new version.
     */
    public synchronized java.util.Set<String> updateSubgraph(String name,
            String newHash, String sourceDocId, Map<String, byte[]> classes,
            List<String> calleeNames, boolean hasAsyncBoundary) throws Exception {
        String oldHash = activeHashByName.get(name);
        java.util.Set<String> affected = oldHash == null
                ? java.util.Set.of()
                : callerSetsByKey.getOrDefault(name + "@" + oldHash,
                        java.util.Set.of());
        publishSubgraph(name, newHash, sourceDocId, classes, calleeNames,
                hasAsyncBoundary);
        if (oldHash != null && !oldHash.equals(newHash)) {
            SubgraphEntry old = subgraphs.remove(name + "@" + oldHash);
            if (old != null) {
                disable(old.graph.id());
                graphs.remove(old.graph.id());
            }
            callerSetsByKey.remove(name + "@" + oldHash);
        }
        for (String caller : affected) {
            if (graphs.containsKey(caller)) {
                disable(caller);
            }
        }
        return affected;
    }

    private Object runSubgraphCall(String nameAndHash, Object[] args,
            InvocationContext callerCtx) throws Exception {
        SubgraphEntry entry = subgraphs.get(nameAndHash);
        if (entry == null) {
            throw new IllegalArgumentException("Unknown subgraph: " + nameAndHash);
        }
        int depth = subgraphDepth.get();
        if (depth >= MAX_SUBGRAPH_DEPTH) {
            throw new IllegalStateException("Subgraph recursion depth exceeded "
                    + MAX_SUBGRAPH_DEPTH + ": " + nameAndHash);
        }
        Execution execution = new Execution(executionSeq.incrementAndGet(),
                entry.graph, argsAsPayload(args), maxOperationsPerExecution);
        entry.graph.live.add(execution);
        execution.state = ExecutionState.RUNNING;
        InvocationContext childCtx = new InvocationContext(execution.id,
                callerCtx.cancellation(), callerCtx.budget());
        execution.context = childCtx;
        EngineServices services = new EngineServices(execution, entry.generation);
        subgraphDepth.set(depth + 1);
        try {
            entry.generation.newExecutable().execute("in", execution.payload,
                    childCtx, services);
            settle(execution);
        } catch (GraphReturnSignal signal) {
            execution.state = ExecutionState.COMPLETED;
            entry.graph.live.remove(execution);
            return signal.value();
        } catch (Throwable throwable) {
            fail(execution, entry.generation, throwable);
            throw throwable;
        } finally {
            subgraphDepth.set(depth);
        }
        return null;
    }

    private Map<String, Object> argsAsPayload(Object[] args) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                payload.put("arg" + i, args[i]);
            }
        }
        return payload;
    }

    public LoadedGraph enable(String graphId, Map<String, byte[]> classes,
            ClassLoader abiParent) throws Exception {
        return enable(graphId, classes, abiParent, null);
    }

    public LoadedGraph enable(String graphId, Map<String, byte[]> classes,
            ClassLoader abiParent, SourceMap sourceMap) throws Exception {
        LoadedGraph loaded = graphs.computeIfAbsent(graphId, LoadedGraph::new);
        Generation generation = buildGeneration(graphId, loaded.nextGenerationNumber(),
                classes, abiParent, sourceMap);
        loaded.current = generation;
        loaded.enabled = true;
        return loaded;
    }

    public LoadedGraph update(String graphId, Map<String, byte[]> classes,
            ClassLoader abiParent) throws Exception {
        return update(graphId, classes, abiParent, null);
    }

    public LoadedGraph update(String graphId, Map<String, byte[]> classes,
            ClassLoader abiParent, SourceMap sourceMap) throws Exception {
        LoadedGraph loaded = requireEnabled(graphId);
        Generation fresh = buildGeneration(graphId,
                loaded.nextGenerationNumber(), classes, abiParent, sourceMap);
        Generation previous = loaded.current;
        loaded.current = fresh;
        retireWhenDrained(previous);
        return loaded;
    }

    public boolean disable(String graphId) {
        LoadedGraph loaded = graphs.get(graphId);
        if (loaded == null) {
            return false;
        }
        loaded.enabled = false;
        cancelAllOf(loaded, "graph disabled");
        return true;
    }

    public boolean remove(String graphId) {
        boolean existed = disable(graphId);
        if (!existed) {
            return false;
        }
        LoadedGraph loaded = graphs.remove(graphId);
        graphStores.remove(graphId);
        if (loaded != null && loaded.generation() != null) {
            retireWhenDrained(loaded.generation());
        }
        return true;
    }

    public LoadedGraph status(String graphId) {
        return graphs.get(graphId);
    }

    public Object serverVariable(String name) {
        return scoped.get(ScopedVariables.SERVER, "__global__", name);
    }

    public Object graphVariable(String graphId, String name) {
        VariableStore store = graphStores.get(graphId);
        return store == null ? null : store.get(name);
    }

    public Set<String> graphIds() {
        return Set.copyOf(graphs.keySet());
    }

    public void shutdown() {
        List<String> ids = new ArrayList<>(graphs.keySet());
        for (String id : ids) {
            LoadedGraph loaded = graphs.get(id);
            if (loaded != null) {
                cancelAllOf(loaded, "engine shutdown");
                loaded.enabled = false;
            }
        }
    }

    public long dispatch(String graphId, String eventNodeId,
            Map<String, Object> payload) {
        LoadedGraph loaded = graphs.get(graphId);
        if (loaded == null || !loaded.isEnabled()) {
            throw new IllegalStateException("Graph not enabled: " + graphId);
        }
        Generation generation = loaded.current;
        if (generation == null) {
            throw new IllegalStateException("No compiled generation for " + graphId);
        }
        if (enforceMainThread && !main.isMainThread()) {
            throw new IllegalStateException("Dispatch must happen on the main thread");
        }
        Execution execution = new Execution(executionSeq.incrementAndGet(), loaded,
                payload, maxOperationsPerExecution);
        loaded.live.add(execution);
        execution.state = ExecutionState.RUNNING;
        EngineServices services = new EngineServices(execution, generation);
        try {
            generation.newExecutable().execute(eventNodeId, execution.payload,
                    execution.context, services);
            settle(execution);
        } catch (Throwable throwable) {
            fail(execution, generation, throwable);
        }
        return execution.id;
    }

    private void settle(Execution execution) {
        if (execution.pendingHops.get() > 0) {
            return;
        }
        if (execution.state == ExecutionState.RUNNING) {
            execution.state = ExecutionState.COMPLETED;
            execution.graph().live.remove(execution);
            graph.runtime.DebugHook hook = debugHook;
            if (hook != null) {
                hook.onExecutionEvent(execution.id(), execution.graph().id(),
                        "completed", null, execution.variables.snapshot());
            }
        } else if (execution.state != ExecutionState.SUSPENDED
                && execution.state != ExecutionState.SCHEDULED) {
            execution.graph().live.remove(execution);
        }
    }

    private void fail(Execution execution, Generation generation, Throwable throwable) {
        execution.failure = throwable;
        execution.state = ExecutionState.FAILED;
        execution.graph().live.remove(execution);
        errorSink.accept(attributed(execution, generation, throwable));
        logOnce(execution, throwable);
        graph.runtime.DebugHook hook = debugHook;
        if (hook != null) {
            String node = attributed(execution, generation, throwable).nodeId();
            hook.onExecutionEvent(execution.id(), execution.graph().id(),
                    "failed", node, execution.variables.snapshot());
        }
    }

    private StructuredError attributed(Execution execution, Generation generation,
            Throwable throwable) {
        String nodeId = null;
        String functionId = null;
        SourceMap sourceMap = generation.sourceMap();
        if (sourceMap != null) {
            outer: for (StackTraceElement element : throwable.getStackTrace()) {
                if (!element.getClassName().equals(sourceMap.className())) {
                    continue;
                }
                int line = element.getLineNumber();
                SourceMap.Mapping best = null;
                int bestDistance = Integer.MAX_VALUE;
                for (SourceMap.Mapping mapping : sourceMap.mappings()) {
                    if (mapping.nodeId() == null) {
                        continue;
                    }
                    if (line >= mapping.lineStart() && line <= mapping.lineEnd()) {
                        best = mapping;
                        break;
                    }
                    int distance = Math.min(
                            Math.abs(line - mapping.lineStart()),
                            Math.abs(line - mapping.lineEnd()));
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = mapping;
                    }
                }
                if (best != null) {
                    nodeId = best.nodeId();
                    functionId = best.functionId();
                }
                break outer;
            }
        }
        return new StructuredError(execution.id, execution.graph().id(),
                generation.number(), nodeId, functionId,
                throwable.getClass().getSimpleName(),
                throwable.getMessage(),
                List.of(throwable.getStackTrace()));
    }

    private void logOnce(Execution execution, Throwable throwable) {
        String signature = execution.graph().id() + "|" + throwable.getClass().getName()
                + "|" + throwable.getMessage();
        long now = System.currentTimeMillis();
        Long last = recentSignatures.get(signature);
        if (last != null && now - last < 60_000) {
            return;
        }
        recentSignatures.put(signature, now);
        logger.accept("[graph] '" + execution.graph().id() + "' execution "
                + execution.id + " failed: " + throwable);
    }

    private void cancelAllOf(LoadedGraph loaded, String reason) {
        for (Execution execution : loaded.executions()) {
            execution.cancel(reason);
        }
        for (graph.runtime.ScheduleHandle timer : loaded.timers) {
            timer.cancel();
        }
        loaded.timers.clear();
    }

    private void retireWhenDrained(Generation generation) {
        Thread watcher = new Thread(() -> {
            long deadline = System.currentTimeMillis() + 30_000;
            while (generation.loader().hasLiveUsers() && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            generation.loader().retire();
        }, "graph-retire");
        watcher.setDaemon(true);
        watcher.start();
    }

    private LoadedGraph requireEnabled(String graphId) {
        LoadedGraph loaded = graphs.get(graphId);
        if (loaded == null || !loaded.isEnabled()) {
            throw new IllegalStateException("Graph not enabled: " + graphId);
        }
        return loaded;
    }

    private Generation buildGeneration(String graphId, int number,
            Map<String, byte[]> classes,
            ClassLoader abiParent, SourceMap sourceMap) throws Exception {
        GenerationClassLoader loader = new GenerationClassLoader(classes, abiParent);
        loader.trackLiveUsage();
        String mainClass = mainClassName(classes, graphId);
        Class<?> type = loader.loadMain(mainClass);
        Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        GraphExecutable executable = (GraphExecutable) constructor.newInstance();
        SourceMap qualified = sourceMap == null
                ? null : sourceMap.withClassName(mainClass);
        return new Generation(number, executable, loader, qualified);
    }

    private static String mainClassName(Map<String, byte[]> classes, String graphId) {
        String suffix = JavaGenerator.className(graphId);
        for (String name : classes.keySet()) {
            if (name.endsWith(suffix)) {
                return name;
            }
        }
        throw new IllegalArgumentException(
                "Compiled artifact lacks main class " + suffix);
    }

    public final class EngineServices implements RuntimeServices {

        private final Execution execution;
        private final Generation generation;

        EngineServices(Execution execution, Generation generation) {
            this.execution = execution;
            this.generation = generation;
            execution.onCancel.add(() -> {
                if (execution.state != ExecutionState.CANCELLED) {
                    execution.state = ExecutionState.CANCELLED;
                }
            });
        }

        @Override
        public void debugNode(String nodeId) {
            synchronized (execution.trace) {
                execution.trace.add(nodeId);
            }
            execution.nodeVisitCounts.merge(nodeId, 1L, Long::sum);
            graph.runtime.DebugHook hook = debugHook;
            if (hook == null) {
                return;
            }
            hook.onNodeEnter(execution.id(), execution.graph.id(),
                    generation.number(), nodeId,
                    execution.variables.snapshot());
        }

        @Override
        public void recordNodeTiming(String nodeId, long nanos) {
            execution.nodeTimeNanos.merge(nodeId, nanos, Long::sum);
        }

        @Override
        public Object invokeFunction(String functionId, String overloadHash,
                Object[] args, InvocationContext ctx) throws Exception {
            if (enforceMainThread && !main.isMainThread()) {
                throw new IllegalStateException("Function '" + functionId
                        + "' requires the main thread");
            }
            if (functionId.startsWith("graph:")) {
                callerSetsByKey
                        .computeIfAbsent(functionId.substring(6),
                                k -> java.util.concurrent.ConcurrentHashMap.newKeySet())
                        .add(execution.graph.id());
                return runSubgraphCall(functionId.substring(6), args, ctx);
            }
            Invoker invoker = registry.invoker(functionId);
            if (invoker == null) {
                throw new IllegalArgumentException("Unknown function: " + functionId);
            }
            return invoker.invoke(overloadHash, args, ctx);
        }

        @Override
        public void scheduleResume(double seconds, Runnable continuation) {
            markSuspended();
            Scheduler.Handle handle = scheduler.schedule(seconds, () -> {
                if (execution.context.cancellation().isCancelled()) {
                    return;
                }

                main.post(continuation);
            });
            if (execution.state == ExecutionState.SUSPENDED) {
                execution.state = ExecutionState.SCHEDULED;
            }
            execution.onCancel.add(handle::cancel);
        }

        @Override
        public void awaitWithTimeout(java.util.concurrent.CompletableFuture<?> future,
                double seconds, java.util.function.BiConsumer<Object, Throwable> done) {
            if (!future.isDone()) {
                markSuspended();
            }
            java.util.concurrent.atomic.AtomicBoolean decided =
                    new java.util.concurrent.atomic.AtomicBoolean();
            java.util.concurrent.atomic.AtomicBoolean released =
                    new java.util.concurrent.atomic.AtomicBoolean();
            java.lang.Runnable release = () -> {
                if (released.compareAndSet(false, true)) {
                    execution.pendingHops.decrementAndGet();
                }
            };
            execution.pendingHops.incrementAndGet();
            scheduler.schedule(seconds, () -> {
                if (!decided.compareAndSet(false, true)) {
                    return;
                }
                if (execution.context.cancellation().isCancelled()) {
                    release.run();
                    return;
                }
                main.post(() -> {
                    release.run();
                    done.accept(null,
                            new java.util.concurrent.TimeoutException(
                                    "await timed out after " + seconds + "s"));
                });
            });
            future.whenComplete((value, error) -> {
                if (!decided.compareAndSet(false, true)) {
                    return;
                }
                if (execution.context.cancellation().isCancelled()) {
                    release.run();
                    return;
                }
                main.post(() -> {
                    release.run();
                    done.accept(value, error);
                });
            });
        }

        @Override
        public void postToMain(Runnable runnable) {
            execution.pendingHops.incrementAndGet();
            main.post(() -> {
                try {
                    if (!execution.context.cancellation().isCancelled()) {
                        markRunning();
                        runnable.run();
                    }
                } catch (Throwable t) {
                    fail(execution, generation, t);
                } finally {
                    boolean lastHop = execution.pendingHops.decrementAndGet() == 0;
                    ExecutionState s = execution.state;
                    boolean waiting = s == ExecutionState.SUSPENDED
                            || s == ExecutionState.SCHEDULED
                            || s == ExecutionState.CANCELLED
                            || s == ExecutionState.FAILED
                            || s == ExecutionState.COMPLETED;
                    if (lastHop && !waiting) {
                        settle(execution);
                    }
                }
            });
        }

        @Override
        public void awaitFuture(java.util.concurrent.CompletableFuture<?> future,
                java.util.function.BiConsumer<Object, Throwable> done) {
            if (!future.isDone()) {
                markSuspended();
            }
            java.util.concurrent.atomic.AtomicBoolean released =
                    new java.util.concurrent.atomic.AtomicBoolean();
            java.lang.Runnable release = () -> {
                if (released.compareAndSet(false, true)) {
                    execution.pendingHops.decrementAndGet();
                }
            };
            execution.pendingHops.incrementAndGet();
            future.whenComplete((value, error) -> {
                if (execution.context.cancellation().isCancelled()) {
                    release.run();
                    return;
                }
                main.post(() -> {
                    release.run();
                    done.accept(value, error);
                });
            });
        }

        @Override
        public java.util.concurrent.CompletableFuture<?> dispatchAsync(String functionId, String overloadHash,
                Object[] args, InvocationContext ctx) {
            if (functionId.startsWith("graph:")) {
                try {
                    callerSetsByKey
                            .computeIfAbsent(functionId.substring(6),
                                    k -> java.util.concurrent.ConcurrentHashMap.newKeySet())
                            .add(execution.graph.id());
                    return java.util.concurrent.CompletableFuture.completedFuture(
                            runSubgraphCall(functionId.substring(6), args, ctx));
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
            return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    Invoker invoker = registry.invoker(functionId);
                    if (invoker == null) {
                        throw new IllegalArgumentException("Unknown function: " + functionId);
                    }
                    return invoker.invoke(overloadHash, args, execution.context);
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }

        @Override
        public java.util.concurrent.CompletableFuture<?> httpAsync(String method, String url,
                Map<String, String> headers,
                Map<String, String> query, String body,
                InvocationContext ctx) {
            HttpDelegate delegate = httpDelegate;
            if (delegate == null) {
                throw new UnsupportedOperationException("http node services not wired yet");
            }
            java.util.concurrent.CompletableFuture<?> fut = delegate.execute(
                    execution.graph.id(), method, url, headers, query, body);
            execution.onCancel.add(() -> fut.cancel(true));
            return fut;
        }
        @Override
        public java.util.concurrent.CompletableFuture<?> dbQueryAsync(String sql, Map<String, Object> params,
                InvocationContext ctx) {
            DbDelegate delegate = dbDelegate;
            if (delegate == null) {
                throw new UnsupportedOperationException("db node services not wired yet");
            }
            java.util.concurrent.CompletableFuture<?> fut =
                    delegate.query(execution.graph.id(), sql, params);
            execution.onCancel.add(() -> fut.cancel(true));
            return fut;
        }

        @Override
        public java.util.concurrent.CompletableFuture<?> dbUpdateAsync(String kind, String table,
                Map<String, Object> row,
                InvocationContext ctx) {
            DbDelegate delegate = dbDelegate;
            if (delegate == null) {
                throw new UnsupportedOperationException("db node services not wired yet");
            }
            java.util.concurrent.CompletableFuture<?> fut =
                    delegate.update(execution.graph.id(), kind, table, row);
            execution.onCancel.add(() -> fut.cancel(true));
            return fut;
        }

        @Override
        public Object startSchedule(String mode, double value, Runnable onFire,
                                    InvocationContext ctx) {
            graph.runtime.ScheduleHandle handle = new graph.runtime.ScheduleHandle();
            double seconds;
            switch (mode == null ? "" : mode) {
                case "next-tick" -> seconds = 0.0;
                case "ticks" -> seconds = Math.max(0.0, value) / 60.0;
                default -> seconds = Math.max(0.0, value);
            }
            boolean repeating = "every".equals(mode);
            Runnable guarded = () -> {
                if (handle.isCancelled()
                        || execution.context.cancellation().isCancelled()
                        || !execution.graph().isEnabled()) {
                    return;
                }
                main.post(onFire);
            };
            arm(handle, guarded, repeating, seconds);
            execution.graph().timers.add(handle);
            return handle;
        }

        private void arm(graph.runtime.ScheduleHandle handle, Runnable guarded,
                boolean repeating, double interval) {
            scheduler.schedule(interval, () -> {
                if (handle.isCancelled()) {
                    return;
                }
                guarded.run();
                if (repeating) {
                    arm(handle, guarded, true, interval);
                }
            });
        }

        @Override
        public void cancelSchedule(Object timerHandle) {
            if (timerHandle instanceof graph.runtime.ScheduleHandle handle) {
                handle.cancel();
            }
        }

        @Override
        public Object getVariable(String scope, String name) {
            if (ScopedVariables.LOCAL.equals(scope)) {
                return execution.variables.get(name);
            }
            if (ScopedVariables.GRAPH.equals(scope)) {
                return graphStores
                        .getOrDefault(execution.graph().id(), new VariableStore())
                        .get(name);
            }
            return scoped.get(scope, scopeKeyFor(scope), name);
        }

        @Override
        public void setVariable(String scope, String name, Object value) {
            if (ScopedVariables.LOCAL.equals(scope)) {
                execution.variables.set(name, value);
                return;
            }
            if (ScopedVariables.GRAPH.equals(scope)) {
                graphStores.computeIfAbsent(execution.graph().id(),
                        k -> new VariableStore()).set(name, value);
                return;
            }
            scoped.set(scope, scopeKeyFor(scope), name, value);
        }

        private String scopeKeyFor(String scope) {
            String payloadKey = switch (scope) {
                case ScopedVariables.PLAYER -> "player";
                case ScopedVariables.TEAM -> "team";
                case ScopedVariables.WORLD -> "world";
                default -> null;
            };
            if (payloadKey == null) {
                return "__global__";
            }
            Object value = execution.payload.get(payloadKey);
            return value == null ? "__global__" : String.valueOf(value);
        }

        @Override
        public void log(String message) {
            logger.accept(message);
        }

                private void emitExecutionEvent(String event) {
            graph.runtime.DebugHook hook = debugHook;
            if (hook != null) {
                hook.onExecutionEvent(execution.id(), execution.graph.id(),
                        event, null, execution.variables.snapshot());
            }
        }

private void markSuspended() {
            if (execution.state == ExecutionState.RUNNING
                    || execution.state == ExecutionState.PENDING) {
                execution.state = ExecutionState.SUSPENDED;
            }
        }

        private void markRunning() {
            if (execution.state == ExecutionState.SUSPENDED
                    || execution.state == ExecutionState.SCHEDULED) {
                execution.state = ExecutionState.RUNNING;
            }
        }
    }
}
