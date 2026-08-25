package graph.compile;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import graph.compile.ExecutionEngine;
import graph.compile.CompilerService.CompiledClasses;
import graph.compile.TypeChecker.CheckResult;
import graph.format.GraphDocument;
import graph.format.GraphEdge;
import graph.format.GraphNode;
import graph.registry.EventDescriptor;
import graph.registry.FunctionDescriptor;
import graph.registry.GraphRegistry;
import graph.registry.Invoker;
import graph.registry.Overload;
import graph.registry.ParamDescriptor;
import graph.runtime.GraphExecutable;
import graph.runtime.MainThreadDispatcher;
import graph.types.TypeRef;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionEngineSmokeTest {

    private GraphRegistry registry;
    private List<String> logLines;

    @BeforeEach
    void setUp() {
        logLines = new java.util.ArrayList<>();
        registry = new GraphRegistry();
        registry.register(FunctionDescriptor.builder("mindustry.player.sendMessage")
                .overload(Overload.of(TypeRef.BOOLEAN,
                        new ParamDescriptor("player", TypeRef.of("Player")),
                        new ParamDescriptor("message", TypeRef.STRING)))
                .build(), (hash, args, ctx) -> {
            logLines.add("sent:" + args[1] + ":to:" + args[0]);
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
        CheckResult checked = TypeChecker.check(doc, linked);
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

    @Test
    void fullStackDispatchExecutesAndCompletes() throws Exception {
        Pipeline pipeline = pipeline(JOIN + "\n" + """
                {"id":"say","type":"call","function":"mindustry.player.sendMessage",
                 "args":{"message":{"kind":"literal","value":"Welcome"}}}
                """,
                "ev.then -> say.exec",
                "ev.player -> say.player");

        MainThreadDispatcher directMain = new MainThreadDispatcher() {
            @Override public boolean isMainThread() { return true; }
            @Override public void post(Runnable r) { r.run(); }
        };
        ExecutionEngine engine = new ExecutionEngine(directMain, registry,
                error -> { }, logLines::add, 10_000, true);

        Map<String, byte[]> allClasses = Map.of(
                "graph.generated." + JavaGenerator.className("welcome-flow"),
                pipeline.classes());
        ExecutionEngine.LoadedGraph loaded = engine.enable("welcome-flow",
                allClasses, GraphExecutable.class.getClassLoader());
        assertTrue(loaded.isEnabled());

        long executionId = engine.dispatch("welcome-flow", "ev", Map.of(
                "player", "Alice"));

        assertTrue(logLines.stream().anyMatch(l ->
                        l.equals("sent:Welcome:to:Alice")),
                () -> logLines.toString());

        engine.shutdown();
    }
}
