package graph.compile;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import graph.compile.CompilerService.CompiledClasses;
import graph.compile.ExecutionEngine;
import graph.compile.TypeChecker.CheckResult;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionEngineLifecycleTest {

    private GraphRegistry registry;
    private List<String> logs;
    private List<ExecutionEngine.StructuredError> errors;
    private MainThreadDispatcher main;
    private ManualScheduler scheduler;
    private ExecutionEngine engine;

    @BeforeEach
    void setUp() {
        logs = new ArrayList<>();
        errors = new ArrayList<>();
        registry = new GraphRegistry();
        scheduler = new ManualScheduler();
        main = new MainThreadDispatcher() {
            @Override public boolean isMainThread() { return true; }
            @Override public void post(Runnable r) { r.run(); }
        };
        rebuildEngine(10_000);
    }

    private void rebuildEngine(long maxOps) {
        engine = new ExecutionEngine(main, registry,
                errors::add, logs::add, maxOps, true);
        engine.installScheduler(scheduler);
    }

    private static final class ManualScheduler implements ExecutionEngine.Scheduler {

        record Pending(Handle handle, Runnable continuation) {
        }

        final ConcurrentLinkedQueue<Pending> pending = new ConcurrentLinkedQueue<>();

        @Override
        public Handle schedule(double seconds, Runnable continuation) {
            AtomicBoolean cancelled = new AtomicBoolean();
            pending.add(new Pending(new Handle() {
                @Override public void cancel() { cancelled.set(true); }
            }, () -> {
                if (!cancelled.get()) {
                    continuation.run();
                }
            }));
            return new Handle() {
                @Override public void cancel() { cancelled.set(true); }
            };
        }

        int pump(int howMany) {
            int fired = 0;
            while (fired < howMany) {
                Pending next = pending.poll();
                if (next == null) {
                    break;
                }
                next.continuation().run();
                fired++;
            }
            return fired;
        }
    }

    @BeforeEach
    void registerFunctions() {
        registry.register(FunctionDescriptor.builder("mindustry.player.sendMessage")
                .overload(Overload.of(TypeRef.BOOLEAN,
                        new ParamDescriptor("player", TypeRef.of("Player")),
                        new ParamDescriptor("message", TypeRef.STRING)))
                .build(), (hash, args, ctx) -> {
            logs.add("sent:" + args[1] + ":" + args[0]);
            return true;
        });
        registry.register(FunctionDescriptor.builder("test.boom")
                .overload(Overload.of(TypeRef.BOOLEAN))
                .build(), (hash, args, ctx) -> {
            throw new IllegalStateException("boom");
        });
        registry.register(new EventDescriptor("mindustry.event.player.join", "Join",
                List.of(new ParamDescriptor("player", TypeRef.of("Player"))), "", ""));
    }

    private record Artifact(Map<String, byte[]> classes, SourceMap sourceMap) {
    }

    private Artifact compile(String graphId, String nodesJson, String... edges)
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
            nodes.add(GraphNode.of(element.get("id").asText(),
                    element.get("type").asText(), data));
        }
        List<GraphEdge> edgeList = new ArrayList<>();
        for (String pair : edges) {
            String[] parts = pair.split("->");
            edgeList.add(GraphEdge.of(parts[0].trim(), parts[1].trim()));
        }
        GraphDocument doc = GraphDocument.initial(graphId.replace('_', '-'),
                List.of(), nodes, edgeList, null);
        LinkedGraph linked = new Linker(registry).link(doc).graph();
        CheckResult checked = TypeChecker.check(doc, linked);
        ThreadCheckResult threads = ThreadCheck.check(doc, linked);
        Ir.IrGraph irGraph = new Lowerer(doc, linked, threads,
                checked.inferredPorts()).lower().ir();
        irGraph = IrOptimizer.optimize(irGraph, threads);
        JavaGenerator.GeneratedSource src = new JavaGenerator(doc.id()).generate(irGraph);
        CompiledClasses compiled = new CompilerService()
                .compile(src.qualifiedName(), src.source());
        assertTrue(compiled.ok(), () -> String.join("\n", compiled.errors()));
        Map<String, byte[]> classes = new java.util.HashMap<>(compiled.classes());
        return new Artifact(classes, src.sourceMap());
    }

    private static final String JOIN =
            "{\"id\":\"ev\",\"type\":\"event\",\"event\":\"mindustry.event.player.join\"}";

    // ---- 4.1 lifecycle states ------------------------------------------------

    @Test
    void straightLineCompletesAndLeavesLiveSet() throws Exception {
        Artifact artifact = compile("welcome_flow", JOIN + "\n" + """
                {"id":"say","type":"call","function":"mindustry.player.sendMessage",
                 "args":{"message":{"kind":"literal","value":"Welcome"}}}
                """,
                "ev.then -> say.exec",
                "ev.player -> say.player");
        engine.enable("welcome-flow", artifact.classes(),
                GraphExecutable.class.getClassLoader(), artifact.sourceMap());

        long id = engine.dispatch("welcome-flow", "ev", Map.of("player", "Alice"));

        assertTrue(logs.contains("sent:Welcome:Alice"));
        assertEquals(0, engine.status("welcome-flow").liveExecutions());
        assertTrue(errors.isEmpty(), () -> errors.toString());
    }

    @Test
    void delaySuspendsThenResumesToCompletion() throws Exception {
        Artifact artifact = compile("welcome_flow", JOIN + "\n" + """
                {"id":"wait","type":"delay","seconds":5}
                {"id":"say","type":"call","function":"mindustry.player.sendMessage",
                 "args":{"message":{"kind":"literal","value":"late hi"}}}
                """,
                "ev.then -> wait.exec",
                "wait.then -> say.exec",
                "ev.player -> say.player");
        engine.enable("welcome-flow", artifact.classes(),
                GraphExecutable.class.getClassLoader(), artifact.sourceMap());

        long id = engine.dispatch("welcome-flow", "ev", Map.of("player", "Bob"));
        ExecutionEngine.LoadedGraph loaded = engine.status("welcome-flow");
        assertEquals(ExecutionEngine.ExecutionState.SCHEDULED,
                loaded.executions().get(0).state());

        assertEquals(1, scheduler.pump(10));
        assertTrue(logs.contains("sent:late hi:Bob"), () -> logs.toString());
        assertEquals(0, loaded.liveExecutions());
    }

    // ---- 4.2 multi-graph isolation -------------------------------------------

    @Test
    void failingGraphDoesNotAffectSiblingGraphs() throws Exception {
        Artifact bad = compile("bad_graph", JOIN + "\n"
                + "{\"id\":\"kaboom\",\"type\":\"throw\",\"message\":\"always fails\"}",
                "ev.then -> kaboom.exec");
        Artifact good = compile("good_graph", JOIN + "\n" + """
                {"id":"say","type":"call","function":"mindustry.player.sendMessage",
                 "args":{"message":{"kind":"literal","value":"still alive"}}}
                """,
                "ev.then -> say.exec",
                "ev.player -> say.player");

        engine.enable("bad-graph", bad.classes(),
                GraphExecutable.class.getClassLoader(), bad.sourceMap());
        engine.enable("good-graph", good.classes(),
                GraphExecutable.class.getClassLoader(), good.sourceMap());

        for (int i = 0; i < 3; i++) {
            engine.dispatch("bad-graph", "ev", Map.of("player", "P" + i));
        }
        engine.dispatch("good-graph", "ev", Map.of("player", "Healthy"));

        assertEquals(3, errors.size());
        assertTrue(errors.stream().allMatch(e -> e.graphId().equals("bad-graph")));
        assertTrue(logs.contains("sent:still alive:Healthy"),
                "healthy graph must keep working");
    }

    // ---- 4.3 cancellation propagation ----------------------------------------

    @Test
    void disableCancelsSuspendedDelayExecutions() throws Exception {
        Artifact artifact = compile("welcome_flow", JOIN + "\n"
                + "{\"id\":\"wait\",\"type\":\"delay\",\"seconds\":60}",
                "ev.then -> wait.exec");
        engine.enable("welcome-flow", artifact.classes(),
                GraphExecutable.class.getClassLoader(), artifact.sourceMap());

        engine.dispatch("welcome-flow", "ev", Map.of("player", "X"));
        assertEquals(ExecutionEngine.ExecutionState.SCHEDULED,
                engine.status("welcome-flow").executions().get(0).state());

        assertTrue(engine.disable("welcome-flow"));
        assertEquals(ExecutionEngine.ExecutionState.CANCELLED,
                engine.status("welcome-flow").executions().get(0).state());

        scheduler.pump(5);
        assertEquals(ExecutionEngine.ExecutionState.CANCELLED,
                engine.status("welcome-flow").executions().get(0).state(),
                "resume after cancellation must not resurrect the execution");
        assertFalse(engine.status("welcome-flow").isEnabled());
    }

    @Test
    void shutdownCancelsEverythingAcrossGraphs() throws Exception {
        Artifact a = compile("g_one", JOIN + "\n"
                + "{\"id\":\"wait\",\"type\":\"delay\",\"seconds\":30}",
                "ev.then -> wait.exec");
        Artifact b = compile("g_two", JOIN + "\n" + """
                {"id":"wait","type":"delay","seconds":30}
                """,
                "ev.then -> wait.exec");
        engine.enable("g-one", a.classes(),
                GraphExecutable.class.getClassLoader(), a.sourceMap());
        engine.enable("g-two", b.classes(),
                GraphExecutable.class.getClassLoader(), b.sourceMap());
        engine.dispatch("g-one", "ev", Map.of());
        engine.dispatch("g-two", "ev", Map.of());

        engine.shutdown();

        assertEquals(ExecutionEngine.ExecutionState.CANCELLED,
                engine.status("g-one").executions().get(0).state());
        assertEquals(ExecutionEngine.ExecutionState.CANCELLED,
                engine.status("g-two").executions().get(0).state());
    }

    // ---- 4.4/4.6 structured errors + budgets ----------------------------------

    @Test
    void runtimeErrorAttributedToNodeViaSourceMap() throws Exception {
        Artifact artifact = compile("welcome_flow", JOIN + "\n" + """
                {"id":"b","type":"call","function":"test.boom"}
                """,
                "ev.then -> b.exec");
        engine.enable("welcome-flow", artifact.classes(),
                GraphExecutable.class.getClassLoader(), artifact.sourceMap());

        engine.dispatch("welcome-flow", "ev", Map.of("player", "Zed"));

        assertEquals(1, errors.size());
        ExecutionEngine.StructuredError error = errors.get(0);
        assertEquals("IllegalStateException", error.errorType());
        assertEquals("b", error.nodeId(), () -> error.toString());
        assertEquals("welcome-flow", error.graphId());
        assertNotNull(error.stackTrace());
        assertFalse(error.stackTrace().isEmpty());
    }

    @Test
    void infiniteLoopBudgetKillsExecutionAtLoopNode() throws Exception {
        rebuildEngine(50);
        Artifact artifact = compile("welcome_flow", JOIN + "\n" + """
                {"id":"spin","type":"loop","count":-1}
                {"id":"work","type":"log","message":"tick"}
                """,
                "ev.then -> spin.exec",
                "spin.body -> work.exec");
        engine.enable("welcome-flow", artifact.classes(),
                GraphExecutable.class.getClassLoader(), artifact.sourceMap());

        long ticksBefore = logs.stream().filter(l -> l.equals("tick")).count();
        engine.dispatch("welcome-flow", "ev", Map.of());
        long ticksAfter = logs.stream().filter(l -> l.equals("tick")).count();

        org.junit.jupiter.api.Assertions.assertTrue(ticksAfter > ticksBefore,
                "loop must have executed some ticks");
        assertEquals(1, errors.size(), () -> "logs=" + logs.size()
                + " classes=" + artifact.classes().keySet());
        ExecutionEngine.StructuredError error = errors.get(0);
        assertEquals("GraphBudgetExceeded", error.errorType());
        assertEquals("spin", error.nodeId(), () -> error.toString());
        assertEquals(0, engine.status("welcome-flow").liveExecutions());
    }

    // ---- 4.5 main-thread enforcement ------------------------------------------

    @Test
    void mainThreadEnforcementRejectsOffThreadDispatch() throws Exception {
        Artifact artifact = compile("welcome_flow", JOIN + "\n" + """
                {"id":"say","type":"call","function":"mindustry.player.sendMessage",
                 "args":{"message":{"kind":"literal","value":"hi"}}}
                """,
                "ev.then -> say.exec",
                "ev.player -> say.player");
        engine.enable("welcome-flow", artifact.classes(),
                GraphExecutable.class.getClassLoader(), artifact.sourceMap());

        MainThreadDispatcher offThread = new MainThreadDispatcher() {
            @Override public boolean isMainThread() { return false; }
            @Override public void post(Runnable r) { new Thread(r).start(); }
        };
        ExecutionEngine strict = new ExecutionEngine(offThread, registry,
                errors::add, logs::add, 10_000, true);
        strict.installScheduler(scheduler);
        assertThrows(IllegalStateException.class,
                () -> strict.dispatch("welcome-flow", "ev", Map.of()));
    }

    // ---- 4.7 runtime lifecycle -------------------------------------------------

    @Test
    void updateSwapsGenerationWithoutRestart() throws Exception {
        Artifact v1 = compile("welcome_flow", JOIN + "\n" + """
                {"id":"say","type":"call","function":"mindustry.player.sendMessage",
                 "args":{"message":{"kind":"literal","value":"version-1"}}}
                """,
                "ev.then -> say.exec",
                "ev.player -> say.player");
        Artifact v2 = compile("welcome_flow", JOIN + "\n" + """
                {"id":"say","type":"call","function":"mindustry.player.sendMessage",
                 "args":{"message":{"kind":"literal","value":"version-2"}}}
                """,
                "ev.then -> say.exec",
                "ev.player -> say.player");

        engine.enable("welcome-flow", v1.classes(),
                GraphExecutable.class.getClassLoader(), v1.sourceMap());
        int gen1 = engine.status("welcome-flow").generation().number();
        engine.dispatch("welcome-flow", "ev", Map.of("player", "A"));
        assertTrue(logs.contains("sent:version-1:A"));

        engine.update("welcome-flow", v2.classes(),
                GraphExecutable.class.getClassLoader(), v2.sourceMap());
        int gen2 = engine.status("welcome-flow").generation().number();
        assertTrue(gen2 > gen1);

        engine.dispatch("welcome-flow", "ev", Map.of("player", "B"));
        assertTrue(logs.contains("sent:version-2:B"), () -> logs.toString());
        assertFalse(logs.contains("sent:version-2:A"));
    }

    @Test
    void removeMidDelayCancelsCleanlyAndStopsServing() throws Exception {
        Artifact artifact = compile("welcome_flow", JOIN + "\n"
                + "{\"id\":\"wait\",\"type\":\"delay\",\"seconds\":90}",
                "ev.then -> wait.exec");
        engine.enable("welcome-flow", artifact.classes(),
                GraphExecutable.class.getClassLoader(), artifact.sourceMap());
        engine.dispatch("welcome-flow", "ev", Map.of("player", "Gone"));
        assertEquals(ExecutionEngine.ExecutionState.SCHEDULED,
                engine.status("welcome-flow").executions().get(0).state());

        assertTrue(engine.remove("welcome-flow"));
        assertNull(engine.status("welcome-flow"));
        assertThrows(IllegalStateException.class,
                () -> engine.dispatch("welcome-flow", "ev", Map.of()));

        scheduler.pump(3);
        assertTrue(engine.graphIds().isEmpty() || !engine.graphIds().contains("welcome-flow"));
    }

    @Test
    void dispatchOnUnknownOrDisabledGraphRejected() throws Exception {
        Artifact artifact = compile("welcome_flow", JOIN);
        assertThrows(IllegalStateException.class,
                () -> engine.dispatch("never-enabled", "ev", Map.of()));

        engine.enable("welcome-flow", artifact.classes(),
                GraphExecutable.class.getClassLoader(), artifact.sourceMap());
        assertTrue(engine.disable("welcome-flow"));
        assertThrows(IllegalStateException.class,
                () -> engine.dispatch("welcome-flow", "ev", Map.of()));
    }
}

