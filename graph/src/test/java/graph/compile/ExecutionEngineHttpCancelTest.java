package graph.compile;

import graph.compile.ExecutionEngine;
import graph.compile.CompilerService.CompiledClasses;
import graph.format.GraphDocument;
import graph.format.GraphEdge;
import graph.format.GraphNode;
import graph.registry.EventDescriptor;
import graph.registry.GraphRegistry;
import graph.registry.ParamDescriptor;
import graph.runtime.GraphExecutable;
import graph.runtime.MainThreadDispatcher;
import graph.types.TypeRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionEngineHttpCancelTest {

    private GraphRegistry registry;
    private final List<String> errors = new java.util.ArrayList<>();

    @AfterEach
    void dumpErrors() {
        errors.forEach(e -> System.out.println("ENGINE-ERR: " + e));
    }


    @BeforeEach
    void setUp() {
        registry = new GraphRegistry();
        registry.register(new EventDescriptor("mindustry.event.player.join", "Join",
                List.of(new ParamDescriptor("player", TypeRef.of("Player"))), "", ""));
    }

    private record Pipeline(GraphDocument doc, byte[] classes) {
    }

    private Pipeline pipeline(String nodesJson, String... edges) throws Exception {
        String wrapped = "[" + nodesJson.strip().replace("}\n{", "},\n{") + "]";
        var array = new com.fasterxml.jackson.databind.ObjectMapper().readTree(wrapped);
        List<GraphNode> nodes = new java.util.ArrayList<>();
        for (var element : array) {
            var data = new java.util.LinkedHashMap<String, com.fasterxml.jackson.databind.JsonNode>();
            element.fields().forEachRemaining(e -> {
                if (!e.getKey().equals("id") && !e.getKey().equals("type")) {
                    data.put(e.getKey(), e.getValue());
                }
            });
            nodes.add(GraphNode.of(element.get("id").asText(), element.get("type").asText(), data));
        }
        List<GraphEdge> edgeList = new java.util.ArrayList<>();
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

    private static final String JOIN =
            "{\"id\":\"ev\",\"type\":\"event\",\"event\":\"mindustry.event.player.join\"}";

    private static final String HTTP_GET =
            "{\"id\":\"h\",\"type\":\"http-get\",\"properties\":{\"url\":\"http://slow.example/api\",\"timeoutMs\":5000}}";

    private ExecutionEngine newEngine() {
        MainThreadDispatcher directMain = new MainThreadDispatcher() {
            @Override public boolean isMainThread() { return true; }
            @Override public void post(Runnable r) { r.run(); }
        };
        return new ExecutionEngine(directMain, registry,
                error -> errors.add(error.toString()), line -> { }, 10_000, true);
    }

    private ExecutionEngine.LoadedGraph enable(ExecutionEngine engine, byte[] classes)
            throws Exception {
        Map<String, byte[]> allClasses = Map.of(
                "graph.generated." + JavaGenerator.className("welcome-flow"), classes);
        return engine.enable("welcome-flow", allClasses,
                GraphExecutable.class.getClassLoader());
    }

    @Test
    void disablingExecutionCancelsInFlightHttpCall() throws Exception {
        ExecutionEngine engine = newEngine();
        boolean[] cancelled = {false};
        engine.installHttpDelegate((key, method, url, headers, query, body) ->
                new CompletableFuture<Object>() {
                    @Override
                    public boolean cancel(boolean mayInterruptIfRunning) {
                        cancelled[0] = true;
                        return super.cancel(mayInterruptIfRunning);
                    }
                });

        enable(engine, pipeline(JOIN + "\n" + HTTP_GET, "ev.then -> h.exec").classes());
        engine.dispatch("welcome-flow", "ev", Map.of("player", "Alice"));
        ExecutionEngine.Execution exec =
                engine.status("welcome-flow").executions().get(0);
        assertEquals(ExecutionEngine.ExecutionState.SUSPENDED, exec.state());

        assertFalse(cancelled[0]);
        engine.disable("welcome-flow");
        assertEquals(ExecutionEngine.ExecutionState.CANCELLED, exec.state());
        assertTrue(cancelled[0], "delegate future must be cancelled when execution cancels");
    }

    @Test
    void lateCompletionAfterCancelDoesNotReviveExecution() throws Exception {
        ExecutionEngine engine = newEngine();
        CompletableFuture<Object> inflight = new CompletableFuture<>();
        engine.installHttpDelegate((key, method, url, headers, query, body) -> inflight);

        enable(engine, pipeline(JOIN + "\n" + HTTP_GET, "ev.then -> h.exec").classes());
        engine.dispatch("welcome-flow", "ev", Map.of("player", "Alice"));
        ExecutionEngine.Execution exec =
                engine.status("welcome-flow").executions().get(0);
        engine.disable("welcome-flow");
        assertEquals(ExecutionEngine.ExecutionState.CANCELLED, exec.state());

        inflight.complete("{\"ok\":true}");
        assertEquals(ExecutionEngine.ExecutionState.CANCELLED, exec.state());
    }

    @Test
    void completionResumesThroughDoneBranch() throws Exception {
        ExecutionEngine engine = newEngine();
        CompletableFuture<Object> inflight = new CompletableFuture<>();
        engine.installHttpDelegate((key, method, url, headers, query, body) -> inflight);

        enable(engine, pipeline(JOIN + "\n" + HTTP_GET, "ev.then -> h.exec").classes());
        engine.dispatch("welcome-flow", "ev", Map.of("player", "Alice"));
        ExecutionEngine.Execution exec =
                engine.status("welcome-flow").executions().get(0);
        inflight.complete("payload");
        assertEquals(ExecutionEngine.ExecutionState.COMPLETED, exec.state());
    }
}
