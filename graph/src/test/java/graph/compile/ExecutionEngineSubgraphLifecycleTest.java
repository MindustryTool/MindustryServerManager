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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionEngineSubgraphLifecycleTest {

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

    private byte[] trivialSubgraph(String docName) throws Exception {
        docId.set(docName);
        return pipeline("""
                {"id":"in","type":"event","event":"in"}
                {"id":"r","type":"return"}
                """,
                "in.then -> r.exec").classes();
    }

    private byte[] trivialBytes(String docName) throws Exception {
        return trivialSubgraph(docName);
    }

    private ExecutionEngine newEngine() {
        MainThreadDispatcher directMain = new MainThreadDispatcher() {
            @Override public boolean isMainThread() { return true; }
            @Override public void post(Runnable r) { r.run(); }
        };
        ExecutionEngine engine = new ExecutionEngine(directMain, registry,
                error -> errors.add(error.toString()), line -> { }, 100_000, true);
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

    private void registerCallable(String callableId, boolean async) {
        registry.register(FunctionDescriptor.builder(callableId)
                .threadRequirement(async
                        ? ThreadRequirement.ASYNC
                        : ThreadRequirement.MAIN_THREAD)
                .overload(Overload.of(TypeRef.of("Object"),
                        new ParamDescriptor("arg0", TypeRef.of("Object"))))
                .build(), (hash, args, ctx) -> null);
    }

    @Test
    void updateSubgraphRetiresOldHashAndDisablesExactlyItsCallers()
            throws Exception {
        ExecutionEngine engine = newEngine();
        engine.publishSubgraph("double", "v1", "sub-double-1",
                Map.of("graph.generated." + JavaGenerator.className("sub-double-1"),
                        doubleSubgraph("sub-double-1")));
        registerCallable("graph:double@v1", false);

        docId.set("welcome-flow");
        enable(engine, "welcome-flow", pipeline(JOIN + "\n" + """
                {"id":"call","type":"call","function":"C",
                 "args":{"arg0":{"kind":"literal","value":5}}}
                {"id":"c","type":"call","function":"test.collect"}
                """.replace("C", "graph:double@v1"),
                "ev.then -> call.exec",
                "call.then -> c.exec",
                "call.result -> c.value").classes());

        engine.dispatch("welcome-flow", "ev", Map.of());
        assertEquals(Map.of("v", 10), collected.get(0));
        assertTrue(engine.status("welcome-flow").isEnabled());

        java.util.Set<String> affected = engine.updateSubgraph("double", "v2",
                "sub-double-2",
                Map.of("graph.generated." + JavaGenerator.className("sub-double-2"),
                        doubleSubgraph("sub-double-2")),
                List.of(), false);

        assertEquals(java.util.Set.of("welcome-flow"), affected,
                "caller set of retired hash must be invalidated exactly");
        assertFalse(engine.status("welcome-flow").isEnabled(),
                "caller pinned to v1 must be disabled after update");
        assertTrue(errors.isEmpty());

        registerCallable("graph:double@v2", false);
        docId.set("welcome-flow-2");
        enable(engine, "welcome-flow-2", pipeline(JOIN + "\n" + """
                {"id":"call","type":"call","function":"C",
                 "args":{"arg0":{"kind":"literal","value":9}}}
                {"id":"c","type":"call","function":"test.collect"}
                """.replace("C", "graph:double@v2"),
                "ev.then -> call.exec",
                "call.then -> c.exec",
                "call.result -> c.value").classes());
        engine.dispatch("welcome-flow-2", "ev", Map.of());
        assertEquals(Map.of("v", 18), collected.get(1));
    }

    @Test
    void synchronousCycleIsRejectedButAsyncBoundaryCycleIsAccepted()
            throws Exception {
        ExecutionEngine engine = newEngine();

        engine.publishSubgraph("a", "h1", "sg-a",
                bytesFor("sg-a"), List.of("b"), false);
        IllegalArgumentException rejected = assertThrows(
                IllegalArgumentException.class,
                () -> engine.publishSubgraph("b", "h1", "sg-b",
                        bytesFor("sg-b"), List.of("a"), false),
                "a->b->a is a purely synchronous cycle");
        assertTrue(rejected.getMessage().contains("Synchronous recursion cycle"));

        // With the sync pair rejected, unrelated graphs still publish.
        engine.publishSubgraph("c", "h1", "sg-c",
                bytesFor("sg-c"), List.of(), false);

        // A cycle whose newest member carries an async boundary is permitted.
        engine.publishSubgraph("x", "h1", "sg-x",
                bytesFor("sg-x"), List.of("z"), false);
        engine.publishSubgraph("z", "h1", "sg-z",
                bytesFor("sg-z"), List.of("x"), true);
        assertTrue(errors.isEmpty());
    }

    private Map<String, byte[]> bytesFor(String docName) throws Exception {
        return Map.of("graph.generated." + JavaGenerator.className(docName),
                trivialSubgraph(docName));
    }

    @Test
    void asyncSubgraphOutputConsumedWithoutDoubleAwaitAndFutureTypedOutputViaAwait()
            throws Exception {
        ExecutionEngine engine = newEngine();
        engine.publishSubgraph("adouble", "h1", "sg-adouble",
                bytesForDouble("sg-adouble"),
                List.of(), true);
        registerCallable("graph:adouble@h1", true);

        docId.set("welcome-flow");
        enable(engine, "welcome-flow", pipeline(JOIN + "\n" + """
                {"id":"call","type":"call","function":"AC",
                 "args":{"arg0":{"kind":"literal","value":8}}}
                {"id":"c","type":"call","function":"test.collect"}
                """.replace("AC", "graph:adouble@h1"),
                "ev.then -> call.exec",
                "call.then -> c.exec",
                "call.result -> c.value").classes());

        engine.dispatch("welcome-flow", "ev", Map.of());

        assertEquals(1, collected.size(), errors.toString());
        assertEquals(Map.of("v", 16), collected.get(0));
    }

    @Test
    void futureTypedSubgraphReturnIsConsumableThroughAwaitNode() throws Exception {
        registry.register(FunctionDescriptor.builder("test.futureValue")
                .overload(Overload.of(TypeRef.future(TypeRef.of("Object"))))
                .build(), (hash, args, ctx) ->
                java.util.concurrent.CompletableFuture.completedFuture(
                        java.util.Map.of("n", 3)));

        ExecutionEngine engine = newEngine();
        String futDoc = "sg-future";
        docId.set(futDoc);
        byte[] fut = pipeline("""
                {"id":"in","type":"event","event":"in"}
                {"id":"k","type":"call","function":"test.futureValue"}
                {"id":"r","type":"return"}
                """,
                "in.then -> k.exec",
                "k.then -> r.exec",
                "k.result -> r.value").classes();
        engine.publishSubgraph("future", "h9", futDoc,
                Map.of("graph.generated." + JavaGenerator.className(futDoc), fut));
        registry.register(FunctionDescriptor.builder("graph:future@h9")
                .overload(Overload.of(TypeRef.future(TypeRef.of("Object"))))
                .build(), (hash, args, ctx) -> null);

        docId.set("welcome-flow");
        enable(engine, "welcome-flow", pipeline(JOIN + "\n" + """
                {"id":"call","type":"call","function":"FC"}
                {"id":"wait","type":"await"}
                {"id":"c","type":"call","function":"test.collect"}
                """.replace("FC", "graph:future@h9"),
                "ev.then -> call.exec",
                "call.then -> wait.exec",
                "call.result -> wait.future",
                "wait.value -> c.value",
                "wait.then -> c.exec").classes());

        engine.dispatch("welcome-flow", "ev", Map.of());

        assertEquals(1, collected.size(), errors.toString());
        Object delivered = collected.get(0);
        assertTrue(delivered instanceof java.util.Map,
                "await must unwrap the future: " + delivered.getClass());
        assertTrue(String.valueOf(((java.util.Map<?, ?>) delivered).get("n"))
                        .startsWith("3"),
                "unexpected payload: " + delivered);
    }

    private Map<String, byte[]> bytesForDouble(String docName) throws Exception {
        return Map.of("graph.generated." + JavaGenerator.className(docName),
                doubleSubgraph(docName));
    }

    private static final String JOIN =
            "{\"id\":\"ev\",\"type\":\"event\",\"event\":\"mindustry.event.player.join\"}";
}
