package graph.compile;

import graph.compile.ExecutionEngine;
import graph.compile.CompilerService.CompiledClasses;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExecutionEngineDelayStatesTest {

    private GraphRegistry registry;
    private List<String> logLines;

    private static final class ManualScheduler implements ExecutionEngine.Scheduler {
        record Entry(double seconds, Runnable continuation) {
        }

        final List<Entry> entries = new ArrayList<>();
        final List<String> events = new ArrayList<>();

        @Override
        public Handle schedule(double seconds, Runnable continuation) {
            events.add("scheduled:" + seconds);
            Entry entry = new Entry(seconds, () -> {
                events.add("resumed");
                continuation.run();
            });
            entries.add(entry);
            return () -> entries.remove(entry);
        }

        void fireAll() {
            for (Entry entry : List.copyOf(entries)) {
                entry.continuation().run();
            }
        }
    }

    @BeforeEach
    void setUp() {
        logLines = new ArrayList<>();
        registry = new GraphRegistry();
        registry.register(FunctionDescriptor.builder("test.mark")
                .overload(Overload.of(TypeRef.BOOLEAN,
                        new ParamDescriptor("name", TypeRef.STRING)))
                .build(), (hash, args, ctx) -> {
            logLines.add(String.valueOf(args[0]));
            return true;
        });
        registry.register(new EventDescriptor("mindustry.event.player.join", "Join",
                List.of(new ParamDescriptor("player", TypeRef.of("Player"))), "", ""));
    }

    private record Pipeline(GraphDocument doc, byte[] classes) {
    }

    private Pipeline pipeline(String docId, String nodesJson, String... edges) throws Exception {
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
        GraphDocument doc = GraphDocument.initial(docId, List.of(), nodes,
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
                error -> { }, logLines::add, 10_000, true);
        engine.installScheduler(scheduler);
        return engine;
    }

    private void enable(ExecutionEngine engine, String graphId, byte[] classes)
            throws Exception {
        Map<String, byte[]> allClasses = Map.of(
                "graph.generated." + JavaGenerator.className(graphId), classes);
        engine.enable(graphId, allClasses, GraphExecutable.class.getClassLoader());
    }

    @Test
    void delayWalksRunningSuspendedScheduledResumedCompleted() throws Exception {
        ManualScheduler scheduler = new ManualScheduler();
        ExecutionEngine engine = newEngine(scheduler);

        enable(engine, "welcome-flow", pipeline("welcome-flow", JOIN + "\n" +
                "{\"id\":\"d\",\"type\":\"delay\",\"properties\":{\"seconds\":0.5}}",
                "ev.then -> d.exec").classes());

        engine.dispatch("welcome-flow", "ev", Map.of("player", "Alice"));
        ExecutionEngine.Execution exec =
                engine.status("welcome-flow").executions().get(0);
        assertEquals(ExecutionEngine.ExecutionState.SCHEDULED, exec.state());
        assertEquals(List.of("scheduled:0.5"), scheduler.events.subList(0, 1));

        scheduler.fireAll();

        assertEquals(ExecutionEngine.ExecutionState.COMPLETED, exec.state());
        assertEquals(List.of("scheduled:0.5", "resumed"), scheduler.events);
    }

    @Test
    void sameDeadlineDelaysResumeInScheduleOrder() throws Exception {
        ManualScheduler scheduler = new ManualScheduler();
        ExecutionEngine engine = newEngine(scheduler);

        for (String name : List.of("A", "B", "C")) {
            String graphId = "flow-" + name;
            enable(engine, graphId, pipeline(graphId, JOIN + "\n" + """
                    {"id":"d","type":"delay","properties":{"seconds":1.0}}
                    {"id":"m","type":"call","function":"test.mark",
                     "args":{"name":{"kind":"literal","value":"NAME"}}}
                    """.replace("NAME", name),
                    "ev.then -> d.exec", "d.then -> m.exec").classes());
            engine.dispatch(graphId, "ev", Map.of("player", "Alice"));
        }
        assertEquals(3, scheduler.entries.size());

        scheduler.fireAll();

        assertEquals(List.of("A", "B", "C"), logLines.stream()
                .filter(l -> List.of("A", "B", "C").contains(l))
                .toList());
    }

    @Test
    void disableDuringScheduledDropsLateResume() throws Exception {
        ManualScheduler scheduler = new ManualScheduler();
        ExecutionEngine engine = newEngine(scheduler);

        enable(engine, "welcome-flow", pipeline("welcome-flow", JOIN + "\n" +
                "{\"id\":\"d\",\"type\":\"delay\",\"properties\":{\"seconds\":2.0}}",
                "ev.then -> d.exec").classes());

        engine.dispatch("welcome-flow", "ev", Map.of("player", "Alice"));
        ExecutionEngine.Execution exec =
                engine.status("welcome-flow").executions().get(0);
        assertEquals(ExecutionEngine.ExecutionState.SCHEDULED, exec.state());

        engine.disable("welcome-flow");
        assertEquals(ExecutionEngine.ExecutionState.CANCELLED, exec.state());

        scheduler.fireAll();

        assertEquals(ExecutionEngine.ExecutionState.CANCELLED, exec.state());
        assertEquals(List.of("scheduled:2.0"), scheduler.events);
    }
}
