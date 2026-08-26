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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaGeneratorTest {

    private GraphRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new GraphRegistry();
        registry.register(FunctionDescriptor.builder("mindustry.player.sendMessage")
                .overload(Overload.of(TypeRef.BOOLEAN,
                        new ParamDescriptor("player", TypeRef.of("Player")),
                        new ParamDescriptor("message", TypeRef.STRING)))
                .build(), (hash, args, ctx) -> true);
        registry.register(FunctionDescriptor.builder("math.pure")
                .threadRequirement(graph.registry.ThreadRequirement.PURE)
                .overload(Overload.of(TypeRef.INT, new ParamDescriptor("v", TypeRef.INT)))
                .build(), (hash, args, ctx) -> null);
        registry.register(new EventDescriptor("mindustry.event.player.join", "Join",
                List.of(new ParamDescriptor("player", TypeRef.of("Player"))), "", ""));
        registry.register(FunctionDescriptor.builder("net.fetch")
                .threadRequirement(graph.registry.ThreadRequirement.ASYNC)
                .overload(Overload.of(TypeRef.future(TypeRef.STRING),
                        new ParamDescriptor("url", TypeRef.STRING)))
                .build(), (hash, args, ctx) -> null);
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
        GraphDocument doc = GraphDocument.initial("welcome-flow", List.of(), nodes, edgeList, null);
        LinkedGraph linked = new Linker(registry).link(doc).graph();
        return new Built(doc, linked);
    }

    private Ir.IrGraph ir(Built built) {
        CheckResult checked = TypeChecker.check(built.doc(), built.linked());
        ThreadCheckResult threads = ThreadCheck.check(built.doc(), built.linked());
        return new Lowerer(built.doc(), built.linked(), threads,
                checked.inferredPorts()).lower().ir();
    }

    private static final String JOIN =
            "{\"id\":\"ev\",\"type\":\"event\",\"event\":\"mindustry.event.player.join\"}";

    @Test
    void reflectiveDispatchGeneratedWithOverloadHash() throws Exception {
        Built built = build(JOIN + "\n" + """
                {"id":"say","type":"call","function":"mindustry.player.sendMessage",
                 "args":{"message":{"kind":"literal","value":"Welcome"}}}
                """,
                "ev.then -> say.exec",
                "ev.player -> say.player");

        JavaGenerator.GeneratedSource src = new JavaGenerator("welcome-flow")
                .generate(ir(built));

        assertTrue(src.fullySupported());
        assertTrue(src.source().contains(
                        "svc.invokeFunction(\"mindustry.player.sendMessage\", \""),
                () -> src.source());
        assertTrue(src.source().contains("new Object[]{p_ev_player, \"Welcome\"}"),
                () -> src.source());
        assertTrue(src.source().contains("try { __rv = svc.invokeFunction(\"mindustry.player.sendMessage\", \""),
                "result vars are class fields assigned from dispatch");
        assertTrue(src.source().contains("class Graph_welcome_flow implements GraphExecutable"),
                () -> src.source());
        assertTrue(src.source().contains("\"ev\""), "event node id registered");
    }

    @Test
    void dynamicInvokerFallbackWhenNoJavaTarget() throws Exception {
        Built built = build(JOIN + "\n" + """
                {"id":"p","type":"call","function":"math.pure",
                 "args":{"v":{"kind":"literal","value":3}}}
                """,
                "ev.then -> p.exec");

        JavaGenerator.GeneratedSource src = new JavaGenerator("welcome-flow")
                .generate(ir(built));
        assertTrue(src.source().contains("svc.invokeFunction(\"math.pure\""),
                () -> src.source());
    }

    @Test
    void delaySuspensionPatternEmitted() throws Exception {
        Built built = build(JOIN + "\n" + """
                {"id":"wait","type":"delay","seconds":5}
                """,
                "ev.then -> wait.exec");

        JavaGenerator.GeneratedSource src = new JavaGenerator("welcome-flow")
                .generate(ir(built));
        assertTrue(src.source().contains("if (suspended_wait) {"),
                "resume pass clears the flag; fresh pass suspends");
        assertTrue(src.source().contains("suspended_wait = true;"));
        assertTrue(src.source().contains("((Number) (5.0f)).doubleValue()"),
                () -> src.source());
        assertTrue(src.source().contains("svc.postToMain(this::runSegmentsSafe)"),
                () -> src.source());
        assertTrue(src.source().contains("return;"), () -> src.source());
    }

    @Test
    void forEachEmitsBudgetSpend() throws Exception {
        Built built = build(JOIN + "\n" + """
                {"id":"loop","type":"for-each"}
                {"id":"bodylog","type":"log","message":"x"}
                """,
                "ev.then -> loop.exec",
                "loop.body -> bodylog.exec");

        JavaGenerator.GeneratedSource src = new JavaGenerator("welcome-flow")
                .generate(ir(built));
        assertTrue(src.source().contains("ctx.spend(1);"), () -> src.source());
        assertTrue(src.source().contains("for (java.lang.Object item_loop : list_loop) {"),
                () -> src.source());
    }

    @Test
    void suspendInsideLoopReportedUnsupported() throws Exception {
        Built built = build(JOIN + "\n" + """
                {"id":"loop","type":"for-each"}
                {"id":"wait","type":"delay","seconds":1}
                """,
                "ev.then -> loop.exec",
                "loop.body -> wait.exec");

        JavaGenerator.GeneratedSource src = new JavaGenerator("welcome-flow")
                .generate(ir(built));
        assertFalse(src.fullySupported());
        assertEquals("wait", src.unsupported().get(0).nodeId());
    }

    @Test
    void sourceMapCoversStatementsWithNodeIds() throws Exception {
        Built built = build(JOIN + "\n" + """
                {"id":"say","type":"call","function":"mindustry.player.sendMessage",
                 "args":{"message":{"kind":"literal","value":"Welcome"}}}
                """,
                "ev.then -> say.exec",
                "ev.player -> say.player");

        JavaGenerator.GeneratedSource src = new JavaGenerator("welcome-flow")
                .generate(ir(built));

        SourceMap map = src.sourceMap();
        assertEquals("welcome-flow", map.graphId());
        assertEquals("Graph_welcome_flow", map.className());

        boolean evCovered = false;
        boolean sayCovered = false;
        String[] lines = src.source().split("\n");
        for (SourceMap.Mapping mapping : map.mappings()) {
            assertTrue(mapping.lineEnd() >= mapping.lineStart(),
                    "line range must be ordered");
            assertTrue(mapping.lineStart() <= lines.length);
            if ("ev".equals(mapping.nodeId())) {
                evCovered = true;
            }
            if ("say".equals(mapping.nodeId())) {
                sayCovered = true;
                String covered = lines[mapping.lineStart() - 1];
                boolean rangeHasCall = false;
                for (int ln = mapping.lineStart();
                        ln <= Math.min(mapping.lineEnd(), lines.length); ln++) {
                    if (lines[ln - 1].contains("svc.invokeFunction")) {
                        rangeHasCall = true;
                        break;
                    }
                }
                assertTrue(rangeHasCall,
                        "mapped range should contain the generated call: "
                                + covered);
            }
        }
        assertTrue(evCovered && sayCovered,
                () -> "mappings must include both nodes: " + map.mappings());

        int[] seenLines = new int[lines.length + 1];
        for (SourceMap.Mapping mapping : map.mappings()) {
            for (int l = mapping.lineStart(); l <= mapping.lineEnd(); l++) {
                seenLines[l]++;
                assertTrue(seenLines[l] <= 1, "mapping ranges must not overlap");
            }
        }
    }
}


