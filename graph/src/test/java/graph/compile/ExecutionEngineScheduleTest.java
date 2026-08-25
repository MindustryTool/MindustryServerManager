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
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionEngineScheduleTest {

    private GraphRegistry registry;
    private List<String> marks;

    private static final class ManualScheduler implements ExecutionEngine.Scheduler {
        record Entry(double seconds, Runnable continuation) {
        }

        final List<Entry> entries = new ArrayList<>();

        int pending() {
            return entries.size();
        }

        void fireAll() {
            List<Entry> due = List.copyOf(entries);
            entries.clear();
            for (Entry entry : due) {
                entry.continuation().run();
            }
        }

        @Override
        public Handle schedule(double seconds, Runnable continuation) {
            entries.add(new Entry(seconds, continuation));
            return () -> entries.removeIf(e -> e.continuation() == continuation);
        }
    }

    @BeforeEach
    void setUp() {
        marks = Collections.synchronizedList(new ArrayList<>());
        registry = new GraphRegistry();
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

    private static final String JOIN =
            "{\"id\":\"ev\",\"type\":\"event\",\"event\":\"mindustry.event.player.join\"}";

    private ExecutionEngine newEngine(ManualScheduler scheduler) {
        MainThreadDispatcher directMain = new MainThreadDispatcher() {
            @Override public boolean isMainThread() { return true; }
            @Override public void post(Runnable r) { r.run(); }
        };
        ExecutionEngine engine = new ExecutionEngine(directMain, registry,
                error -> { }, line -> { }, 10_000, true);
        engine.installScheduler(scheduler);
        return engine;
    }

    private void enable(ExecutionEngine engine, byte[] classes) throws Exception {
        Map<String, byte[]> allClasses = Map.of(
                "graph.generated." + JavaGenerator.className("welcome-flow"), classes);
        engine.enable("welcome-flow", allClasses,
                GraphExecutable.class.getClassLoader());
    }

    @Test
    void afterModeFiresBodyOnceOnSchedulerThreadHandoff() throws Exception {
        ManualScheduler scheduler = new ManualScheduler();
        ExecutionEngine engine = newEngine(scheduler);

        enable(engine, pipeline(JOIN + "\n" + """
                {"id":"s","type":"schedule","properties":{"mode":"after","seconds":0.25}}
                {"id":"m","type":"call","function":"test.mark",
                 "args":{"name":{"kind":"literal","value":"fired"}}}
                """, "ev.then -> s.exec", "s.body -> m.exec").classes());

        engine.dispatch("welcome-flow", "ev", Map.of("player", "Alice"));
        assertEquals(1, scheduler.pending());
        assertTrue(marks.isEmpty());

        scheduler.fireAll();
        assertEquals(List.of("fired"), marks);

        scheduler.fireAll();
        assertEquals(List.of("fired"), marks);
    }

    @Test
    void everyModeRepeatsUntilDisabled() throws Exception {
        ManualScheduler scheduler = new ManualScheduler();
        ExecutionEngine engine = newEngine(scheduler);

        enable(engine, pipeline(JOIN + "\n" + """
                {"id":"s","type":"schedule","properties":{"mode":"every","seconds":0.5}}
                {"id":"m","type":"call","function":"test.mark",
                 "args":{"name":{"kind":"literal","value":"tick"}}}
                """, "ev.then -> s.exec", "s.body -> m.exec").classes());

        engine.dispatch("welcome-flow", "ev", Map.of("player", "Alice"));

        scheduler.fireAll();
        assertEquals(List.of("tick"), marks);
        assertEquals(1, scheduler.pending(), "repeating timer must re-arm");

        scheduler.fireAll();
        assertEquals(2, marks.size());
        assertTrue(engine.disable("welcome-flow"));

        engine.remove("welcome-flow");
        scheduler.fireAll();
        assertEquals(2, marks.size(), "disabled graph timers must not fire");
    }

    @Test
    void cancelNodeStopsPendingTimerBeforeFire() throws Exception {
        ManualScheduler scheduler = new ManualScheduler();
        ExecutionEngine engine = newEngine(scheduler);

        enable(engine, pipeline(JOIN + "\n" + """
                {"id":"s","type":"schedule","properties":{"mode":"after","seconds":5.0}}
                {"id":"c","type":"cancel-schedule"}
                {"id":"m","type":"call","function":"test.mark",
                 "args":{"name":{"kind":"literal","value":"never"}}}
                """, "ev.then -> s.exec", "s.then -> c.exec", "s.result -> c.handle",
                "s.body -> m.exec").classes());

        engine.dispatch("welcome-flow", "ev", Map.of("player", "Alice"));
        assertEquals(1, scheduler.pending());

        scheduler.fireAll();
        assertTrue(marks.isEmpty(), "cancelled timer must not fire its body");

        scheduler.fireAll();
        assertTrue(marks.isEmpty());
    }
}
