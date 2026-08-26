package graph.compile;

import graph.compile.ExecutionEngine;
import graph.compile.CompilerService.CompiledClasses;
import graph.compile.DebugSessionManager.PauseContext;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionEngineDebugTest {

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
                .build(), (hash, args, ctx) -> "alice");
        registry.register(new EventDescriptor("mindustry.event.player.join", "Join",
                List.of(), "", ""));
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
        return pipeline(List.of(), nodesJson, edges);
    }

    private Pipeline pipeline(List<graph.format.VariableDecl> variables,
            String nodesJson, String... edges) throws Exception {
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
        GraphDocument doc = GraphDocument.initial("welcome-flow", variables, nodes,
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

    private static final List<graph.format.VariableDecl> VARS =
            List.of(new graph.format.VariableDecl("who", "LOCAL", "String"));

private static final String JOIN =
            "{\"id\":\"ev\",\"type\":\"event\",\"event\":\"mindustry.event.player.join\"}";

    private static final String FLOW = JOIN + "\n" + """
            {"id":"k","type":"call","function":"test.greeting"}
            {"id":"sv","type":"set-variable","scope":"LOCAL","variable":"who"}
            {"id":"fin","type":"call","function":"test.collect",
             "args":{"value":{"kind":"literal","value":"done"}}}
            """;

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
                "graph.generated." + JavaGenerator.className("welcome-flow"),
                classes);
        engine.enable("welcome-flow", allClasses,
                GraphExecutable.class.getClassLoader());
    }

    @Test
    void detachedPrologueAddsNoBehavior() throws Exception {
        ExecutionEngine engine = newEngine();
        enable(engine, pipeline(VARS, FLOW,
                "ev.then -> k.exec",
                "k.then -> sv.exec",
                "k.result -> sv.value",
                "sv.then -> fin.exec").classes());

        engine.dispatch("welcome-flow", "ev", Map.of("player", "alice"));

        assertEquals(List.of("done"), collected);
        assertTrue(errors.isEmpty());
    }

    @Test
    void breakpointPausesBeforeNodeWithVariableSnapshotThenResumes()
            throws Exception {
        ExecutionEngine engine = newEngine();
        DebugSessionManager session = new DebugSessionManager();
        engine.installDebugHook(session);

        enable(engine, pipeline(VARS, FLOW,
                "ev.then -> k.exec",
                "k.then -> sv.exec",
                "k.result -> sv.value",
                "sv.then -> fin.exec").classes());

        session.attach();
        session.addBreakpoint("fin");
        List<PauseContext> pauses = new ArrayList<>();
        session.onPause(p -> {
            pauses.add(p);
            p.resume();
        });

        engine.dispatch("welcome-flow", "ev", Map.of("player", "alice"));

        assertEquals(1, pauses.size(), errors.toString());
        PauseContext pause = pauses.get(0);
        assertEquals("fin", pause.nodeId());
        assertEquals("alice", pause.variables().get("who"));
        assertEquals(List.of("done"), collected);

        session.detach();
        collected.clear();
        engine.dispatch("welcome-flow", "ev", Map.of("player", "bob"));
        assertTrue(pauses.size() == 1, "detached session must not pause");
        assertEquals(List.of("done"), collected);
    }

    @Test
    void stepModePausesPerNodeUntilBudgetExhausted() throws Exception {
        ExecutionEngine engine = newEngine();
        DebugSessionManager session = new DebugSessionManager();
        engine.installDebugHook(session);

        enable(engine, pipeline(VARS, FLOW,
                "ev.then -> k.exec",
                "k.then -> sv.exec",
                "k.result -> sv.value",
                "sv.then -> fin.exec").classes());

        session.attach();
        AtomicInteger pauseCount = new AtomicInteger();
        List<String> pausedNodes = new ArrayList<>();
        session.onPause(p -> {
            pausedNodes.add(p.nodeId());
            pauseCount.incrementAndGet();
            p.resume();
        });
        session.step(2);

        engine.dispatch("welcome-flow", "ev", Map.of());

        assertEquals(2, pauseCount.get());
        assertEquals(List.of("k", "sv"), pausedNodes);
        assertEquals(List.of("done"), collected);
    }

    @Test
    void cancelFromPauseFailsOnlyThatExecution() throws Exception {
        ExecutionEngine engine = newEngine();
        DebugSessionManager session = new DebugSessionManager();
        engine.installDebugHook(session);

        enable(engine, pipeline(VARS, FLOW,
                "ev.then -> k.exec",
                "k.then -> sv.exec",
                "k.result -> sv.value",
                "sv.then -> fin.exec").classes());

        session.attach();
        session.addBreakpoint("fin");
        List<PauseContext> pauses = new ArrayList<>();
        session.onPause(p -> {
            pauses.add(p);
            p.cancelExecution();
        });

        engine.dispatch("welcome-flow", "ev", Map.of());

        assertEquals(1, pauses.size());
        assertTrue(collected.isEmpty(),
                "cancelled execution must not reach its collect node");
        assertTrue(errors.toString().contains("debug session cancelled")
                || errors.toString().contains("cancelled"),
                errors.toString());
        assertTrue(engine.status("welcome-flow")
                .executions().isEmpty());
    }
}
