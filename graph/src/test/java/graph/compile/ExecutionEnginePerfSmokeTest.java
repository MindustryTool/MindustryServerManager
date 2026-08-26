package graph.compile;

import graph.compile.ExecutionEngine;
import graph.compile.CompilerService.CompiledClasses;
import graph.format.GraphDocument;
import graph.format.GraphEdge;
import graph.format.GraphNode;
import graph.registry.EventDescriptor;
import graph.registry.GraphRegistry;
import graph.runtime.GraphExecutable;
import graph.runtime.MainThreadDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionEnginePerfSmokeTest {

    private GraphRegistry registry;
    private final List<String> errors = new ArrayList<>();

    private static final class ManualScheduler implements ExecutionEngine.Scheduler {
        final List<Runnable> pending = new ArrayList<>();

        @Override
        public Handle schedule(double seconds, Runnable continuation) {
            pending.add(continuation);
            return () -> pending.remove(continuation);
        }
    }

    @BeforeEach
    void setUp() {
        errors.clear();
        registry = new GraphRegistry();
        registry.register(new EventDescriptor("mindustry.event.player.join", "Join",
                List.of(), "", ""));
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
        GraphDocument doc = GraphDocument.initial(docId.get(), List.of(), nodes,
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

    private final ThreadLocal<String> docId =
            ThreadLocal.withInitial(() -> "welcome-flow");

    private static final String JOIN =
            "{\"id\":\"ev\",\"type\":\"event\",\"event\":\"mindustry.event.player.join\"}";

    private ExecutionEngine newEngine(ManualScheduler scheduler) {
        MainThreadDispatcher directMain = new MainThreadDispatcher() {
            @Override public boolean isMainThread() { return true; }
            @Override public void post(Runnable r) { r.run(); }
        };
        ExecutionEngine engine = new ExecutionEngine(directMain, registry,
                error -> errors.add(error.toString()), line -> { }, 10_000, true);
        engine.installScheduler(scheduler);
        return engine;
    }

    @Test
    void tenThousandTrivialDispatchesStayFastAndErrorFree() throws Exception {
        ManualScheduler scheduler = new ManualScheduler();
        ExecutionEngine engine = newEngine(scheduler);

        Pipeline p = pipeline(JOIN + "\n" +
                "{\"id\":\"noop\",\"type\":\"log\"}",
                "ev.then -> noop.exec");
        Map<String, byte[]> classes = Map.of(
                "graph.generated." + JavaGenerator.className("welcome-flow"),
                p.classes());
        engine.enable("welcome-flow", classes,
                GraphExecutable.class.getClassLoader());

        long start = System.nanoTime();
        int ticks = 10_000;
        for (int i = 0; i < ticks; i++) {
            engine.dispatch("welcome-flow", "ev", Map.of());
        }
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertEquals(0, engine.status("welcome-flow").liveExecutions(),
                "all dispatches must have completed");
        assertTrue(errors.isEmpty(), errors.toString());
        // Generous CI-safe ceiling: 10k trivial dispatches must not take >5s.
        assertTrue(elapsedMillis < 5_000,
                "10k dispatches took " + elapsedMillis + "ms");
    }

    @Test
    void thousandsOfSuspendedExecutionsHoldFlatThreadCount() throws Exception {
        ManualScheduler scheduler = new ManualScheduler();
        ExecutionEngine engine = newEngine(scheduler);

        Pipeline p = pipeline(JOIN + "\n" +
                "{\"id\":\"d\",\"type\":\"delay\",\"properties\":{\"seconds\":60}}",
                "ev.then -> d.exec");
        engine.enable("welcome-flow", Map.of(
                        "graph.generated." + JavaGenerator.className("welcome-flow"),
                        p.classes()),
                GraphExecutable.class.getClassLoader());

        int threadsBefore = Thread.activeCount();
        int count = 2_000;
        for (int i = 0; i < count; i++) {
            engine.dispatch("welcome-flow", "ev", Map.of());
        }

        System.out.println("PROBE live=" + engine.status("welcome-flow").liveExecutions()
                + " pending=" + scheduler.pending.size() + " errs=" + errors.size());
        assertEquals(count, engine.status("welcome-flow").liveExecutions(),
                "every suspended execution must stay live");
        assertEquals(count, scheduler.pending.size());
        int threadsAfter = Thread.activeCount();
        assertTrue(threadsAfter <= threadsBefore + 5,
                "suspended executions must not spawn threads: "
                        + threadsBefore + " -> " + threadsAfter);
    }

    @Test
    void manyGraphConcurrentTriggeringAllComplete() throws Exception {
        ManualScheduler scheduler = new ManualScheduler();
        ExecutionEngine engine = newEngine(scheduler);

        int graphCount = 100;
        for (int g = 0; g < graphCount; g++) {
            String id = "flow-" + g;
            docId.set(id);
            Pipeline p = pipeline(JOIN + "\n" +
                    "{\"id\":\"noop\",\"type\":\"log\"}",
                    "ev.then -> noop.exec");
            engine.enable(id, Map.of(
                            "graph.generated." + JavaGenerator.className(id),
                            p.classes()),
                    GraphExecutable.class.getClassLoader());
        }

        long start = System.nanoTime();
        for (int g = 0; g < graphCount; g++) {
            engine.dispatch("flow-" + g, "ev", Map.of());
        }
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMillis < 5_000,
                "triggering 100 graphs took " + elapsedMillis + "ms");
        assertTrue(errors.isEmpty(), errors.toString());
    }
}
