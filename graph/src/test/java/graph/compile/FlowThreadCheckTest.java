package graph.compile;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import graph.format.GraphDocument;
import graph.format.GraphEdge;
import graph.format.GraphNode;
import graph.format.ValidationResult;
import graph.registry.EventDescriptor;
import graph.registry.FunctionDescriptor;
import graph.registry.GraphRegistry;
import graph.registry.Overload;
import graph.registry.ParamDescriptor;
import graph.types.TypeRef;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowThreadCheckTest {

    private GraphRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new GraphRegistry();
        registry.register(FunctionDescriptor.builder("mindustry.player.sendMessage")
                .overload(Overload.of(TypeRef.BOOLEAN,
                        new ParamDescriptor("player", TypeRef.of("Player")),
                        new ParamDescriptor("message", TypeRef.STRING)))
                .build(), (hash, args, ctx) -> true);
        registry.register(FunctionDescriptor.builder("net.fetch")
                .threadRequirement(graph.registry.ThreadRequirement.ASYNC)
                .overload(Overload.of(TypeRef.future(TypeRef.STRING),
                        new ParamDescriptor("url", TypeRef.STRING)))
                .build(), (hash, args, ctx) -> null);
        registry.register(new EventDescriptor("mindustry.event.player.join", "Join",
                List.of(new ParamDescriptor("player", TypeRef.of("Player"))), "", ""));
    }

    private record Compiled(GraphDocument doc, LinkedGraph linked) {
    }

    private Compiled compile(String nodesJson, String... edges) throws Exception {
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
        GraphDocument doc = GraphDocument.initial("t", List.of(), nodes, edgeList, null);
        LinkedGraph linked = new Linker(registry).link(doc).graph();
        return new Compiled(doc, linked);
    }

    private static final String JOIN_EVENT =
            "{\"id\":\"ev\",\"type\":\"event\",\"event\":\"mindustry.event.player.join\"}";

    @Test
    void asyncCallsAreMarkedForAsyncDispatch() throws Exception {
        Compiled c = compile(JOIN_EVENT + "\n" + """
                {"id":"fetch","type":"call","function":"net.fetch"}
                """, "ev.then -> fetch.exec");
        ThreadCheckResult threads = ThreadCheck.check(c.doc(), c.linked());
        assertTrue(threads.requiresAsyncDispatch("fetch"));
        assertFalse(threads.asyncDispatchNodes().isEmpty());

        Compiled sync = compile(JOIN_EVENT + "\n" + """
                {"id":"say","type":"call","function":"mindustry.player.sendMessage",
                 "args":{"message":{"kind":"literal","value":"hi"}}}
                """, "ev.then -> say.exec");
        ThreadCheckResult syncThreads = ThreadCheck.check(sync.doc(), sync.linked());
        assertTrue(syncThreads.asyncDispatchNodes().isEmpty());
    }

    @Test
    void straightLineFlowHasNoCycleAndNoWarnings() throws Exception {
        Compiled c = compile(JOIN_EVENT + "\n" + """
                {"id":"say","type":"call","function":"mindustry.player.sendMessage",
                 "args":{"message":{"kind":"literal","value":"hi"}}}
                {"id":"log1","type":"log","message":"done"}
                """,
                "ev.then -> say.exec",
                "say.then -> log1.exec");
        ValidationResult result = FlowCheck.check(c.doc(), c.linked(),
                ThreadCheck.check(c.doc(), c.linked()));
        assertTrue(result.ok(), () -> result.diagnostics().toString());
        assertTrue(result.diagnostics().isEmpty(),
                () -> result.diagnostics().toString());
    }

    @Test
    void synchronousCycleRejected() throws Exception {
        Compiled c = compile(JOIN_EVENT + "\n" + """
                {"id":"a","type":"log","message":"a"}
                {"id":"b","type":"log","message":"b"}
                """,
                "ev.then -> a.exec",
                "a.then -> b.exec",
                "b.then -> a.exec");
        ValidationResult result = FlowCheck.check(c.doc(), c.linked(),
                ThreadCheck.check(c.doc(), c.linked()));
        assertFalse(result.ok());
        assertTrue(result.diagnostics().stream()
                .anyMatch(d -> d.code().equals("E_SYNC_CYCLE")));
    }

    @Test
    void cycleThroughDelayAllowed() throws Exception {
        Compiled c = compile(JOIN_EVENT + "\n" + """
                {"id":"tick","type":"delay","seconds":1}
                {"id":"beat","type":"log","message":"heartbeat"}
                """,
                "ev.then -> tick.exec",
                "tick.then -> beat.exec",
                "beat.then -> tick.exec");
        ValidationResult result = FlowCheck.check(c.doc(), c.linked(),
                ThreadCheck.check(c.doc(), c.linked()));
        assertTrue(result.ok(), () -> result.diagnostics().toString());
    }

    @Test
    void unreachableFlowNodeWarns() throws Exception {
        Compiled c = compile(JOIN_EVENT + "\n" + """
                {"id":"orphan","type":"log","message":"never runs"}
                """);
        ValidationResult result = FlowCheck.check(c.doc(), c.linked(),
                ThreadCheck.check(c.doc(), c.linked()));
        assertTrue(result.ok(), "warnings must not fail validation");
        assertTrue(result.diagnostics().stream().anyMatch(d ->
                d.code().equals("W_UNREACHABLE") && d.nodeId().equals("orphan")));
    }
}

