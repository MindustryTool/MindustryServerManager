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
        private final InvocationContext context;
        private final VariableStore variables = new VariableStore();
        private final Map<String, Object> payload;
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
            generation.executable().execute(eventNodeId, execution.payload,
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
        public Object invokeFunction(String functionId, String overloadHash,
                Object[] args, InvocationContext ctx) throws Exception {
            if (enforceMainThread && !main.isMainThread()) {
                throw new IllegalStateException("Function '" + functionId
                        + "' requires the main thread");
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
