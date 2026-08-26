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

class ExecutionEngineTraceTest {

    private GraphRegistry registry;
    private final List<String> errors = new ArrayList<>();

    @BeforeEach
    void setUp() {
        errors.clear();
        registry = new GraphRegistry();
        registry.register(new EventDescriptor("mindustry.event.player.join", "Join",
                List.of(), "", ""));
        registry.register(FunctionDescriptor.builder("test.inner")
                .overload(Overload.of(TypeRef.of("Object")))
                .build(), (hash, args, ctx) -> {
            throw new IllegalStateException("deep-boom");
        });
        registry.register(FunctionDescriptor.builder("test.passthrough")
                .threadRequirement(graph.registry.ThreadRequirement.ASYNC)
                .overload(Overload.of(TypeRef.future(TypeRef.of("Object")),
                        new ParamDescriptor("v", TypeRef.of("Object"))))
                .build(), (hash, args, ctx) -> args[0]);
    }

    private record Pipeline(GraphDocument doc, byte[] classes,
            graph.compile.SourceMap sourceMap) {
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
        return new Pipeline(doc, compiled.classes().get(src.qualifiedName()),
                src.sourceMap());
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

    private void enable(ExecutionEngine engine, byte[] classes,
            graph.compile.SourceMap sourceMap) throws Exception {
        Map<String, byte[]> allClasses = Map.of(
                "graph.generated." + JavaGenerator.className("welcome-flow"),
                classes);
        engine.enable("welcome-flow", allClasses,
                GraphExecutable.class.getClassLoader(), sourceMap);
    }

    @Test
    void traceRecordsNodeOrderAndTimingsForCallNodes() throws Exception {
        final ExecutionEngine[] holder = new ExecutionEngine[1];
        ExecutionEngine engine = newEngine();
        holder[0] = engine;
        graph.runtime.DebugHook capture = new graph.runtime.DebugHook() {
            @Override
            public void onNodeEnter(long executionId, String graphId,
                    int generation, String nodeId,
                    Map<String, Object> variableSnapshot) {
                lastExecutionId = executionId;
            }
        };
        engine.installDebugHook(capture);

        Pipeline p1 = pipeline(JOIN + "\n" + """
                {"id":"first","type":"call","function":"test.passthrough",
                 "args":{"v":{"kind":"literal","value":1}}}
                {"id":"second","type":"call","function":"test.passthrough",
                 "args":{"v":{"kind":"literal","value":2}}}
                {"id":"park","type":"delay","properties":{"seconds":60}}
                """,
                "ev.then -> first.exec",
                "first.then -> second.exec",
                "second.then -> park.exec");

        // passthrough is ASYNC: its dispatch future completes immediately and
        // the internal await resumes inline; delay keeps the execution live.
        enable(engine, p1.classes(), p1.sourceMap());
        engine.dispatch("welcome-flow", "ev", Map.of());

        assertTrue(lastExecutionId > 0);
        ExecutionEngine.Execution ex = engine.status("welcome-flow")
                .executions().stream()
                .filter(e -> e.id() == lastExecutionId)
                .findFirst().orElseThrow();

        // Async nodes appear twice: suspend entry + resume entry.
        assertEquals(List.of("first", "first", "second", "second", "park"),
                ex.trace());
        assertEquals(2, ex.nodeVisitCount("first"));
        assertTrue(ex.nodeTimeNanos("first") >= 0);
        assertTrue(ex.nodeTimeNanos("second") >= 0);
    }

    private volatile long lastExecutionId;

    @Test
    void deepFailureAttributesToCallingNodeViaSourceMap() throws Exception {
        registry.register(FunctionDescriptor.builder("test.outer2")
                .overload(Overload.of(TypeRef.of("Object"),
                        new ParamDescriptor("v", TypeRef.of("Object"))))
                .build(), (hash, args, ctx) -> {
            throw new IllegalStateException("outer-threw");
        });

        ExecutionEngine engine = newEngine();
        Pipeline bp = pipeline(JOIN + "\n" + """
                {"id":"boom","type":"call","function":"test.outer2",
                 "args":{"v":{"kind":"literal","value":1}}}
                """,
                "ev.then -> boom.exec");
        enable(engine, bp.classes(), bp.sourceMap());

        engine.dispatch("welcome-flow", "ev", Map.of());

        assertTrue(errors.toString().contains("outer-threw"), errors.toString());
        assertTrue(errors.toString().contains("\"nodeId\":\"boom\"")
                        || errors.toString().contains("nodeId=boom"),
                "structured error must attribute to the call node: " + errors);
    }
}
