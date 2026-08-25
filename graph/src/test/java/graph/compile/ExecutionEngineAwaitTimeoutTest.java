package graph.compile;

import graph.compile.ExecutionEngine;
import graph.compile.CompilerService.CompiledClasses;
import graph.format.GraphDocument;
import graph.format.GraphEdge;
import graph.format.GraphNode;
import graph.registry.EventDescriptor;
import graph.registry.FunctionDescriptor;
import graph.registry.GraphRegistry;
import graph.registry.Overload;
import graph.registry.ParamDescriptor;
import graph.registry.ThreadRequirement;
import graph.runtime.GraphExecutable;
import graph.runtime.MainThreadDispatcher;
import graph.types.TypeRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionEngineAwaitTimeoutTest {

    private GraphRegistry registry;
    private CompletableFuture<Object> io;
    private CompletableFuture<Object> hang;
    private List<String> marks;
    private final List<String> errors = new ArrayList<>();

    private static final class QueueMain implements MainThreadDispatcher {
        final List<Runnable> queue = new ArrayList<>();

        @Override public boolean isMainThread() { return true; }
        @Override public void post(Runnable r) { queue.add(r); }

        void drain() {
            int guard = 0;
            while (!queue.isEmpty() && guard++ < 100) {
                List<Runnable> due = List.copyOf(queue);
                queue.clear();
                for (Runnable r : due) {
                    r.run();
                }
            }
        }
    }

    private static final class ManualScheduler implements ExecutionEngine.Scheduler {
        final List<Runnable> continuations = new ArrayList<>();

        void fireAll() {
            List<Runnable> due = List.copyOf(continuations);
            continuations.clear();
            due.forEach(Runnable::run);
        }

        @Override
        public Handle schedule(double seconds, Runnable continuation) {
            continuations.add(continuation);
            return () -> continuations.remove(continuation);
        }
    }

    @BeforeEach
    void setUp() {
        io = new CompletableFuture<>();
        hang = new CompletableFuture<>();
        marks = new ArrayList<>();
        errors.clear();
        registry = new GraphRegistry();
        registry.register(FunctionDescriptor.builder("test.asyncFetch")
                .threadRequirement(ThreadRequirement.ASYNC)
                .overload(Overload.of(TypeRef.future(TypeRef.of("Object"))))
                .build(), (hash, args, ctx) -> io);
        registry.register(FunctionDescriptor.builder("test.hang")
                .threadRequirement(ThreadRequirement.ASYNC)
                .overload(Overload.of(TypeRef.future(TypeRef.of("Object"))))
                .build(), (hash, args, ctx) -> hang);
        registry.register(FunctionDescriptor.builder("test.mark")
                .overload(Overload.of(TypeRef.BOOLEAN,
                        new ParamDescriptor("name", TypeRef.STRING)))
                .build(), (hash, args, ctx) -> {
            marks.add(String.valueOf(args[0]));
            return true;
        });
        registry.register(new EventDescriptor("mindustry.event.player.join", "Join",
                List.of(new ParamDescriptor("player", TypeRef.of("Player"))), "", ""));
    }

    private record Pipeline(GraphDocument doc, byte[] classes) {
    }

    private Pipeline pipeline(String nodesJson, String... edges) throws Exception {
        String wrapped = "[" + nodesJson.strip().replace("}\n{", "},\n{") + "]";
        var array = new com.fasterxml.jackson.databind.ObjectMapper().readTree(wrapped);
        List<GraphNode> nodes = new ArrayList<>();
        for (var element : array) {
            var data = new java.util.LinkedHashMap<String, com.fasterxml.jackson.databind.JsonNode>();
            element.fields().forEachRemaining(e -> {
                if (!e.getKey().equals("id") && !e.getKey().equals("type")) {
                    data.put(e.getKey(), e.getValue());
                }
            });
            nodes.add(GraphNode.of(element.get("id").asText(), element.get("type").asText(), data));
        }
        List<GraphEdge> edgeList = new ArrayList<>();
        for (String pair : edges) {
            String[] parts = pair.split("->");
            edgeList.add(GraphEdge.of(parts[0].trim(), parts[1].trim()));
        }
        GraphDocument doc = GraphDocument.initial("welcome-flow", List.of(), nodes,
                edgeList, null);
        LinkedGraph linked = new Linker(registry).link(doc).graph();
        TypeChecker.CheckResult checked = TypeChecker.check(doc, linked);
        ThreadCheckResult threads = ThreadCheck.check(doc, linked);
        Ir.IrGraph irGraph = new Lowerer(doc, linked, threads,
                checked.inferredPorts()).lower().ir();
        irGraph = IrOptimizer.optimize(irGraph, threads);
        JavaGenerator.GeneratedSource src = new JavaGenerator(doc.id()).generate(irGraph);
        CompiledClasses compiled = new CompilerService().compile(src.qualifiedName(),
                src.source());
        assertTrue(compiled.ok(), () -> String.join("\n", compiled.errors()));
        return new Pipeline(doc, compiled.classes().get(src.qualifiedName()));
    }

    private byte[] compile(double timeoutSeconds) throws Exception {
        return compile("test.asyncFetch", timeoutSeconds);
    }

    private byte[] compile(String fn, double timeoutSeconds) throws Exception {
        String json = JOIN + "\n" + """
                {"id":"k","type":"call","function":"FN"}
                {"id":"w","type":"await","properties":{"timeoutSeconds":SEC}}
                {"id":"m","type":"call","function":"test.mark",
                 "args":{"name":{"kind":"literal","value":"got"}}}
                """.replace("FN", fn).replace("SEC", String.valueOf(timeoutSeconds));
        return pipeline(json,
                "ev.then -> k.exec",
                "k.then -> w.exec",
                "k.result -> w.future",
                "w.value -> m.name",
                "w.then -> m.exec").classes();
    }

    private static final String JOIN =
            "{\"id\":\"ev\",\"type\":\"event\",\"event\":\"mindustry.event.player.join\"}";

    private ExecutionEngine newEngine(QueueMain main, ManualScheduler scheduler) {
        ExecutionEngine engine = new ExecutionEngine(main, registry,
                error -> errors.add(error.toString()), line -> { }, 10_000, true);
        engine.installScheduler(scheduler);
        return engine;
    }

    private void enable(ExecutionEngine engine, byte[] classes) throws Exception {
        Map<String, byte[]> allClasses = Map.of(
                "graph.generated." + JavaGenerator.className("welcome-flow"), classes);
        engine.enable("welcome-flow", allClasses,
                GraphExecutable.class.getClassLoader());
    }

    private ExecutionEngine.Execution current(ExecutionEngine engine) {
        return engine.status("welcome-flow").executions().get(0);
    }

    @Test
    void ioThreadCompletionResumesOnMainThreadWithValue() throws Exception {
        QueueMain main = new QueueMain();
        ManualScheduler scheduler = new ManualScheduler();
        ExecutionEngine engine = newEngine(main, scheduler);
        enable(engine, compile(30.0));

        engine.dispatch("welcome-flow", "ev", Map.of("player", "Alice"));
        ExecutionEngine.Execution ex = current(engine);
        assertEquals(ExecutionEngine.ExecutionState.SUSPENDED, ex.state());

        io.complete("payload-42");
        assertEquals(ExecutionEngine.ExecutionState.SUSPENDED, ex.state());
        System.out.println("PROBE2 sz=" + engine.status("welcome-flow").executions().size());
        assertTrue(marks.isEmpty());

        main.drain();
        assertEquals(ExecutionEngine.ExecutionState.COMPLETED, ex.state());
        assertEquals(List.of("payload-42"), marks);
    }

    @Test
    void timeoutFailsExecutionWhenFutureNeverCompletes() throws Exception {
        QueueMain main = new QueueMain();
        ManualScheduler scheduler = new ManualScheduler();
        ExecutionEngine engine = newEngine(main, scheduler);
        enable(engine, compile("test.hang", 0.25));

        engine.dispatch("welcome-flow", "ev", Map.of("player", "Alice"));
        ExecutionEngine.Execution ex = current(engine);
        assertTrue(marks.isEmpty());

        main.drain();
        scheduler.fireAll();
        main.drain();

        assertEquals(ExecutionEngine.ExecutionState.FAILED, ex.state());
        assertTrue(errors.toString().contains("timed out"), errors.toString());
    }

    @Test
    void lateTimerAfterCompletionIsHarmless() throws Exception {
        QueueMain main = new QueueMain();
        ManualScheduler scheduler = new ManualScheduler();
        ExecutionEngine engine = newEngine(main, scheduler);
        enable(engine, compile(60.0));

        engine.dispatch("welcome-flow", "ev", Map.of("player", "Alice"));
        System.out.println("PROBE4 sz=" + engine.status("welcome-flow").executions().size() + " errs=" + errors);
        ExecutionEngine.Execution ex = current(engine);
        io.complete("done");
        main.drain();
        assertEquals(ExecutionEngine.ExecutionState.COMPLETED, ex.state());

        scheduler.fireAll();
        assertEquals(ExecutionEngine.ExecutionState.COMPLETED, ex.state());
        assertEquals(List.of("done"), marks);
    }
}
