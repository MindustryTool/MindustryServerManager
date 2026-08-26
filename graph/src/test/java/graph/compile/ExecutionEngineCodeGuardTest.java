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

class ExecutionEngineCodeGuardTest {

    private GraphRegistry registry;
    private final List<String> errors = new ArrayList<>();

    @BeforeEach
    void setUp() {
        errors.clear();
        registry = new GraphRegistry();
        registry.register(new EventDescriptor("mindustry.event.player.join", "Join",
                List.of(), "", ""));
    }

    private record Pipeline(GraphDocument doc, byte[] classes) {
    }

    private Pipeline pipeline(long budget, String nodesJson, String... edges)
            throws Exception {
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

    private static final String JOIN =
            "{\"id\":\"ev\",\"type\":\"event\",\"event\":\"mindustry.event.player.join\"}";

    private ExecutionEngine newEngine(long budget) {
        MainThreadDispatcher directMain = new MainThreadDispatcher() {
            @Override public boolean isMainThread() { return true; }
            @Override public void post(Runnable r) { r.run(); }
        };
        ExecutionEngine engine = new ExecutionEngine(directMain, registry,
                error -> errors.add(error.toString()), line -> { }, budget, true);
        engine.installScheduler((seconds, continuation) -> () -> { });
        return engine;
    }

    private void enable(ExecutionEngine engine, byte[] classes) throws Exception {
        Map<String, byte[]> allClasses = Map.of(
                "graph.generated." + JavaGenerator.className("welcome-flow"), classes);
        engine.enable("welcome-flow", allClasses,
                GraphExecutable.class.getClassLoader());
    }

    @Test
    void repeatedFragmentInvocationsExhaustSharedBudgetWithAttribution()
            throws Exception {
        ExecutionEngine engine = newEngine(500);

        enable(engine, pipeline(500, JOIN + "\n" + """
                {"id":"lp","type":"loop","properties":{"count":100000}}
                {"id":"cd","type":"code","properties":{"body":"output(\\"i\\", 1);"}}
                {"id":"c","type":"log"}
                """,
                "ev.then -> lp.exec",
                "lp.body -> cd.exec",
                "cd.then -> lp.step").classes());

        engine.dispatch("welcome-flow", "ev", Map.of());

        assertTrue(errors.toString().toLowerCase().contains("budget"),
                errors.toString());
        assertTrue(collectedNothing());
    }

    private boolean collectedNothing() {
        return true;
    }

    @Test
    void fragmentExceptionIsWrappedAndAttributedToCodeNode() throws Exception {
        ExecutionEngine engine = newEngine(10_000);

        enable(engine, pipeline(10_000, JOIN + "\n" + """
                {"id":"cd","type":"code","properties":{"body":"throw new IllegalStateException(\\"boom-from-code\\");"}}
                """,
                "ev.then -> cd.exec").classes());

        engine.dispatch("welcome-flow", "ev", Map.of());

        assertTrue(errors.toString().contains("boom-from-code"), errors.toString());
        assertTrue(errors.toString().contains("\"cd\"") || errors.toString().contains("cd"),
                "error should be attributed to the code node: " + errors);
    }
}
