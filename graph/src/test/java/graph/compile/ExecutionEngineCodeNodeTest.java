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
import graph.runtime.GraphExecutable;
import graph.runtime.MainThreadDispatcher;
import graph.types.TypeRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionEngineCodeNodeTest {

    private GraphRegistry registry;
    private List<Object> collected;
    private final List<String> errors = new ArrayList<>();

    @BeforeEach
    void setUp() {
        collected = new ArrayList<>();
        errors.clear();
        registry = new GraphRegistry();
        registry.register(FunctionDescriptor.builder("test.greeting")
                .overload(Overload.of(TypeRef.STRING))
                .build(), (hash, args, ctx) -> "hello");
        registry.register(FunctionDescriptor.builder("test.collect")
                .overload(Overload.of(TypeRef.BOOLEAN,
                        new ParamDescriptor("value", TypeRef.of("Object"))))
                .build(), (hash, args, ctx) -> {
            collected.add(args[0]);
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

    private static final String JOIN =
            "{\"id\":\"ev\",\"type\":\"event\",\"event\":\"mindustry.event.player.join\"}";

    private ExecutionEngine newEngine() {
        MainThreadDispatcher directMain = new MainThreadDispatcher() {
            @Override public boolean isMainThread() { return true; }
            @Override public void post(Runnable r) { r.run(); }
        };
        ExecutionEngine engine = new ExecutionEngine(directMain, registry,
                error -> errors.add(error.toString()), line -> { }, 10_000, true);
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
    void codeBodyReadsInputsAndPublishesOutputs() throws Exception {
        ExecutionEngine engine = newEngine();
        enable(engine, pipeline(JOIN + "\n" + """
                {"id":"k","type":"call","function":"test.greeting"}
                {"id":"cd","type":"code","properties":{"body":"output(\\"len\\", ((String) input(\\"msg\\")).length());"}}
                {"id":"c","type":"call","function":"test.collect"}
                """,
                "ev.then -> k.exec",
                "k.then -> cd.exec",
                "k.result -> cd.msg",
                "cd.then -> c.exec",
                "cd.result -> c.value").classes());

        engine.dispatch("welcome-flow", "ev", Map.of("player", "Alice"));

        assertEquals(1, collected.size());
        assertEquals(Map.of("len", 5), collected.get(0));
    }

    @Test
    void unknownInputFailsExecutionWithDiagnostic() throws Exception {
        ExecutionEngine engine = newEngine();
        enable(engine, pipeline(JOIN + "\n" + """
                {"id":"cd","type":"code","properties":{"body":"output(\\"x\\", input(\\"missing\\"));"}}
                {"id":"c","type":"call","function":"test.collect"}
                """,
                "ev.then -> cd.exec",
                "cd.then -> c.exec",
                "cd.result -> c.value").classes());

        engine.dispatch("welcome-flow", "ev", Map.of("player", "Alice"));

        assertTrue(collected.isEmpty());
        assertTrue(errors.toString().contains("unknown input: missing"),
                errors.toString());
    }
}
