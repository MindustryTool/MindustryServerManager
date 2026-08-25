package graph.compile;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import graph.compile.TypeChecker.CheckResult;
import graph.format.GraphDocument;
import graph.format.GraphEdge;
import graph.format.GraphNode;
import graph.registry.EventDescriptor;
import graph.registry.FunctionDescriptor;
import graph.registry.GraphRegistry;
import graph.registry.Overload;
import graph.registry.ParamDescriptor;
import graph.types.TypeRef;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IrOptimizerTest {

    private GraphRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new GraphRegistry();
        registry.register(FunctionDescriptor.builder("math.pure")
                .threadRequirement(graph.registry.ThreadRequirement.PURE)
                .overload(Overload.of(TypeRef.INT, new ParamDescriptor("v", TypeRef.INT)))
                .build(), (hash, args, ctx) -> null);
        registry.register(new EventDescriptor("mindustry.event.player.join", "Join",
                List.of(), "", ""));
    }

    private record Built(GraphDocument doc, LinkedGraph linked) {
    }

    private Built build(String nodesJson, String... edges) throws Exception {
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
        GraphDocument doc = GraphDocument.initial("flow", List.of(), nodes, edgeList, null);
        LinkedGraph linked = new Linker(registry).link(doc).graph();
        return new Built(doc, linked);
    }

    private Lowerer.LowerResult lower(Built built) {
        CheckResult checked = TypeChecker.check(built.doc(), built.linked());
        ThreadCheckResult threads = ThreadCheck.check(built.doc(), built.linked());
        return new Lowerer(built.doc(), built.linked(), threads,
                checked.inferredPorts()).lower();
    }

    private static final String JOIN =
            "{\"id\":\"ev\",\"type\":\"event\",\"event\":\"mindustry.event.player.join\"}";

    @Test
    void constantConditionFoldsToBranchBody() throws Exception {
        Built built = build(JOIN + "\n" + """
                {"id":"gate","type":"if","args":{"condition":{"kind":"literal","value":true}}}
                {"id":"hot","type":"log","message":"taken"}
                {"id":"cold","type":"log","message":"skipped"}
                """,
                "ev.then -> gate.exec",
                "gate.then -> hot.exec",
                "gate.else -> cold.exec");

        Ir.IrGraph optimized = IrOptimizer.optimize(lower(built).ir(),
                ThreadCheck.check(built.doc(), built.linked()));

        List<Ir.IrStmt> body = optimized.entries().get(0).body();
        assertEquals(1, body.size());
        Ir.LogStmt log = assertInstanceOf(Ir.LogStmt.class, body.get(0));
        assertTrue(log.message().toString().contains("taken"),
                () -> "then-branch must be spliced in: " + body);
    }

    @Test
    void duplicatePureCallsCollapseWithAlias() {
        ThreadCheckResult threads = new ThreadCheckResult(
                graph.format.ValidationResult.pass(),
                java.util.Set.of(), java.util.Set.of("c1", "c2"));

        Ir.InvokeStmt c1 = new Ir.InvokeStmt("c1", "math.pure", "h1", null, null,
                List.of(new Ir.LiteralValue(TypeRef.INT, "7")),
                "v_c1", TypeRef.INT, false);
        Ir.InvokeStmt c2 = new Ir.InvokeStmt("c2", "math.pure", "h1", null, null,
                List.of(new Ir.LiteralValue(TypeRef.INT, "7")),
                "v_c2", TypeRef.INT, false);
        Ir.LogStmt reader = new Ir.LogStmt("log",
                new Ir.PortRef("c2", "result", TypeRef.INT));

        IrOptimizer.DedupResult dedup = IrOptimizer.dedupPureCalls(
                List.of(c1, c2, reader), threads);

        long invokeCount = dedup.statements().stream()
                .filter(s -> s instanceof Ir.InvokeStmt)
                .count();
        assertEquals(1, invokeCount);
        assertEquals("c1", dedup.alias().get("c2"));

        List<Ir.IrStmt> rewritten = IrOptimizer.rewriteNodes(dedup.statements(),
                id -> dedup.alias().getOrDefault(id, id));
        boolean readsC1 = rewritten.stream()
                .anyMatch(s -> s instanceof Ir.LogStmt log
                        && log.message() instanceof Ir.PortRef ref
                        && ref.nodeId().equals("c1"));
        assertTrue(readsC1,
                () -> "aliased readers must point at the surviving call: " + rewritten);
    }

    @Test
    void unreferencedPureResultsEliminated() throws Exception {
        Built built = build(JOIN + "\n" + """
                {"id":"dead","type":"call","function":"math.pure",
                 "args":{"v":{"kind":"literal","value":7}}}
                """,
                "ev.then -> dead.exec");

        Lowerer.LowerResult lowered = lower(built);
        ThreadCheckResult threads = ThreadCheck.check(built.doc(), built.linked());
        Ir.IrGraph optimized = IrOptimizer.optimize(lowered.ir(), threads);

        List<Ir.IrStmt> body = optimized.entries().get(0).body();
        assertTrue(body.stream().noneMatch(s -> s.nodeId().equals("dead")),
                () -> "pure result nobody reads must be eliminated: " + body);
    }
}


