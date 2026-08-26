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

class ExecutionEngineSubgraphTest {

    private GraphRegistry registry;
    private List<Object> collected;
    private final List<String> errors = new ArrayList<>();
    private final ThreadLocal<String> docId =
            ThreadLocal.withInitial(() -> "welcome-flow");

    @BeforeEach
    void setUp() {
        collected = new ArrayList<>();
        errors.clear();
        docId.set("welcome-flow");
        registry = new GraphRegistry();
        registry.register(new EventDescriptor("mindustry.event.player.join", "Join",
                List.of(), "", ""));
        registry.register(new EventDescriptor("in", "In",
                List.of(new ParamDescriptor("arg0", TypeRef.of("Object"))), "", ""));
        registry.register(FunctionDescriptor.builder("test.collect")
                .overload(Overload.of(TypeRef.BOOLEAN,
                        new ParamDescriptor("value", TypeRef.of("Object"))))
                .build(), (hash, args, ctx) -> {
            collected.add(args[0]);
            return true;
        });
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

    private void enable(ExecutionEngine engine, String graphId, byte[] classes)
            throws Exception {
        Map<String, byte[]> allClasses = Map.of(
                "graph.generated." + JavaGenerator.className(graphId), classes);
        engine.enable(graphId, allClasses,
                GraphExecutable.class.getClassLoader());
    }

    private byte[] doubleSubgraph(String docName) throws Exception {
        docId.set(docName);
        return pipeline("""
                {"id":"in","type":"event","event":"in"}
                {"id":"cd","type":"code","properties":{"body":"output(\\"v\\", ((Number) input(\\"x\\")).intValue() * 2);"}}
                {"id":"r","type":"return"}
                """,
                "in.then -> cd.exec",
                "in.arg0 -> cd.x",
                "cd.then -> r.exec",
                "cd.result -> r.value").classes();
    }

    @Test
    void callNodeInvokesPublishedSubgraphAndReceivesReturnValue() throws Exception {
        String callableId = "graph:double@h1";
        byte[] sub = doubleSubgraph("sub-double");

        ExecutionEngine engine = newEngine(10_000);
        engine.publishSubgraph("double", "h1", "sub-double",
                Map.of("graph.generated." + JavaGenerator.className("sub-double"), sub));

        registry.register(FunctionDescriptor.builder(callableId)
                .overload(Overload.of(TypeRef.of("Object"),
                        new ParamDescriptor("arg0", TypeRef.of("Object"))))
                .build(), (hash, args, ctx) -> null);

        docId.set("welcome-flow");
        enable(engine, "welcome-flow", pipeline(JOIN + "\n" + """
                {"id":"call","type":"call","function":"CALLABLE",
                 "args":{"arg0":{"kind":"literal","value":21}}}
                {"id":"c","type":"call","function":"test.collect"}
                """.replace("CALLABLE", callableId),
                "ev.then -> call.exec",
                "call.then -> c.exec",
                "call.result -> c.value").classes());

        engine.dispatch("welcome-flow", "ev", Map.of());

        assertEquals(1, collected.size(), errors.toString());
        assertEquals(Map.of("v", 42), collected.get(0));
    }

    @Test
    void runawaySynchronousRecursionIsContainedByDepthCap() throws Exception {
        String callableId = "graph:spin@h2";
        registry.register(FunctionDescriptor.builder(callableId)
                .overload(Overload.of(TypeRef.of("Object")))
                .build(), (hash, args, ctx) -> null);

        docId.set("sub-spin");
        byte[] sub = pipeline("""
                {"id":"in","type":"event","event":"in"}
                {"id":"again","type":"call","function":"SELF"}
                {"id":"r","type":"return"}
                """.replace("SELF", callableId),
                "in.then -> again.exec",
                "again.then -> r.exec").classes();

        ExecutionEngine engine = newEngine(10_000_000);
        engine.publishSubgraph("spin", "h2", "sub-spin",
                Map.of("graph.generated." + JavaGenerator.className("sub-spin"), sub));

        docId.set("welcome-flow");
        enable(engine, "welcome-flow", pipeline(JOIN + "\n" + """
                {"id":"kick","type":"call","function":"SELF"}
                """.replace("SELF", callableId),
                "ev.then -> kick.exec").classes());

        try {
            engine.dispatch("welcome-flow", "ev", Map.of());
        } catch (RuntimeException expected) {
            String message = expected.getMessage() == null
                    ? String.valueOf(expected.getCause()) : expected.getMessage();
            assertTrue(message.contains("recursion depth"), message);
            return;
        }
        assertTrue(errors.toString().contains("recursion depth"), errors.toString());
    }
}
