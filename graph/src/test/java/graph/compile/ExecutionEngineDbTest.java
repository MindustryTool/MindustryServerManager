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
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionEngineDbTest {

    private GraphRegistry registry;
    private List<Object> collected;
    private List<String> recordedSql;
    private List<String> recordedUpdates;

    private static final class ManualScheduler implements ExecutionEngine.Scheduler {
        @Override
        public Handle schedule(double seconds, Runnable continuation) {
            return () -> { };
        }
    }

    @BeforeEach
    void setUp() {
        collected = new ArrayList<>();
        recordedSql = new ArrayList<>();
        recordedUpdates = new ArrayList<>();
        registry = new GraphRegistry();
        registry.register(FunctionDescriptor.builder("test.sql")
                .overload(Overload.of(TypeRef.STRING))
                .build(), (hash, args, ctx) -> "SELECT id FROM players WHERE score > ?");
        registry.register(FunctionDescriptor.builder("test.table")
                .overload(Overload.of(TypeRef.STRING))
                .build(), (hash, args, ctx) -> "players");
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

    private ExecutionEngine newEngine(ExecutionEngine.DbDelegate db) {
        MainThreadDispatcher directMain = new MainThreadDispatcher() {
            @Override public boolean isMainThread() { return true; }
            @Override public void post(Runnable r) { r.run(); }
        };
        ExecutionEngine engine = new ExecutionEngine(directMain, registry,
                error -> { }, line -> { }, 10_000, true);
        engine.installScheduler(new ManualScheduler());
        engine.installDbDelegate(db);
        return engine;
    }

    private void enable(ExecutionEngine engine, byte[] classes) throws Exception {
        Map<String, byte[]> allClasses = Map.of(
                "graph.generated." + JavaGenerator.className("welcome-flow"), classes);
        engine.enable("welcome-flow", allClasses,
                GraphExecutable.class.getClassLoader());
    }

    @Test
    void queryNodeDeliversTypedRowsThroughDelegate() throws Exception {
        ExecutionEngine engine = newEngine(new ExecutionEngine.DbDelegate() {
            @Override
            public CompletableFuture<?> query(String key, String sql,
                    Map<String, Object> params) {
                recordedSql.add(sql + "|" + params);
                return CompletableFuture.completedFuture(
                        List.of(Map.of("id", 7)));
            }

            @Override
            public CompletableFuture<?> update(String key, String kind,
                    String table, Map<String, Object> row) {
                return CompletableFuture.completedFuture(0);
            }
        });

        enable(engine, pipeline(JOIN + "\n" + """
                {"id":"k","type":"call","function":"test.sql"}
                {"id":"d","type":"db-query"}
                {"id":"c","type":"call","function":"test.collect"}
                """,
                "ev.then -> k.exec",
                "k.then -> d.exec",
                "k.result -> d.sql",
                "d.rows -> c.value",
                "d.then -> c.exec").classes());

        engine.dispatch("welcome-flow", "ev", Map.of("player", "Alice"));

        assertEquals(1, collected.size());
        assertEquals(List.of(Map.of("id", 7)), collected.get(0));
        assertTrue(recordedSql.get(0).startsWith("SELECT id FROM players"),
                recordedSql.toString());
    }

    @Test
    void updateNodeReturnsAffectedCountAndKind() throws Exception {
        ExecutionEngine engine = newEngine(new ExecutionEngine.DbDelegate() {
            @Override
            public CompletableFuture<?> query(String key, String sql,
                    Map<String, Object> params) {
                return CompletableFuture.completedFuture(List.of());
            }

            @Override
            public CompletableFuture<?> update(String key, String kind,
                    String table, Map<String, Object> row) {
                recordedUpdates.add(kind + "|" + table + "|" + row);
                return CompletableFuture.completedFuture(3);
            }
        });

        enable(engine, pipeline(JOIN + "\n" + """
                {"id":"t","type":"call","function":"test.table"}
                {"id":"u","type":"db-insert"}
                {"id":"c","type":"call","function":"test.collect"}
                """,
                "ev.then -> t.exec",
                "t.then -> u.exec",
                "t.result -> u.table",
                "u.count -> c.value",
                "u.then -> c.exec").classes());

        engine.dispatch("welcome-flow", "ev", Map.of("player", "Alice"));

        assertEquals(1, collected.size());
        assertEquals(3, ((Number) collected.get(0)).intValue());
        assertEquals(1, recordedUpdates.size());
        assertTrue(recordedUpdates.get(0).startsWith("db-insert|players|"),
                recordedUpdates.toString());
    }
}
