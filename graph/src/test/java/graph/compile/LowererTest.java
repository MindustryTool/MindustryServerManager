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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LowererTest {

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

    private static final String JOIN =
            "{\"id\":\"ev\",\"type\":\"event\",\"event\":\"mindustry.event.player.join\"}";

    @Test
    void lowersEventCallChainWithLiterals() throws Exception {
        Built built = build(JOIN + "\n" + """
                {"id":"say","type":"call","function":"mindustry.player.sendMessage",
                 "args":{"message":{"kind":"literal","value":"Welcome"}}}
                """,
                "ev.then -> say.exec",
                "ev.player -> say.player");

        CheckResult checked = TypeChecker.check(built.doc(), built.linked());
        ThreadCheckResult threads = ThreadCheck.check(built.doc(), built.linked());
        Lowerer.LowerResult result = new Lowerer(built.doc(), built.linked(),
                threads, checked.inferredPorts()).lower();

        assertTrue(result.ok(), () -> result.diagnostics().toString());
        assertEquals(1, result.ir().entries().size());
        assertEquals(1, result.ir().entries().get(0).body().size());

        Ir.InvokeStmt invoke = assertInstanceOf(Ir.InvokeStmt.class,
                result.ir().entries().get(0).body().get(0));
        assertEquals("say", invoke.nodeId());
        assertEquals("plugin.graph.facades.PlayerFacades", invoke.ownerClass());
        assertEquals("sendMessage", invoke.staticMethod());
        assertFalse(invoke.asyncDispatch());
        assertEquals(2, invoke.args().size());
        Ir.PortRef playerArg = assertInstanceOf(Ir.PortRef.class, invoke.args().get(0));
        assertEquals("ev", playerArg.nodeId());
        assertEquals("player", playerArg.port());
        Ir.LiteralValue message = assertInstanceOf(Ir.LiteralValue.class, invoke.args().get(1));
        assertEquals("\"Welcome\"", message.javaSource());
        assertEquals("v_say", invoke.resultVar(),
                "boolean-returning facade allocates a result variable");
        assertEquals(TypeRef.BOOLEAN, invoke.resultType());
    }

    @Test
    void asyncCallMarkedInIrAndResumeSlotAllocatedForDelay() throws Exception {
        Built built = build(JOIN + "\n" + """
                {"id":"wait","type":"delay","seconds":5}
                {"id":"fetch","type":"call","function":"net.fetch"}
                """,
                "ev.then -> wait.exec",
                "wait.then -> fetch.exec");

        CheckResult checked = TypeChecker.check(built.doc(), built.linked());
        ThreadCheckResult threads = ThreadCheck.check(built.doc(), built.linked());
        Lowerer.LowerResult result = new Lowerer(built.doc(), built.linked(),
                threads, checked.inferredPorts()).lower();

        assertTrue(result.ok());
        assertEquals(1, result.resumeSlotCount());

        List<Ir.IrStmt> body = result.ir().entries().get(0).body();
        Ir.DelayStmt delay = assertInstanceOf(Ir.DelayStmt.class, body.get(0));
        assertEquals(0, delay.resumeSlot());
        Ir.InvokeStmt fetch = assertInstanceOf(Ir.InvokeStmt.class, body.get(1));
        assertTrue(fetch.asyncDispatch());
        assertEquals("v_fetch", fetch.resultVar());
    }

    @Test
    void ifBranchesLoweredRecursively() throws Exception {
        Built built = build(JOIN + "\n" + """
                {"id":"gate","type":"if","args":{"condition":{"kind":"literal","value":true}}}
                {"id":"inside","type":"log","message":"in"}
                {"id":"after","type":"log","message":"out"}
                """,
                "ev.then -> gate.exec",
                "gate.then -> inside.exec",
                "gate.else -> after.exec");

        CheckResult checked = TypeChecker.check(built.doc(), built.linked());
        ThreadCheckResult threads = ThreadCheck.check(built.doc(), built.linked());
        Lowerer.LowerResult result = new Lowerer(built.doc(), built.linked(),
                threads, checked.inferredPorts()).lower();

        assertTrue(result.ok(), () -> result.diagnostics().toString());
        List<Ir.IrStmt> body = result.ir().entries().get(0).body();
        Ir.IfStmt ifStmt = assertInstanceOf(Ir.IfStmt.class, body.get(0));
        assertEquals(1, ifStmt.thenBranch().size());
        assertEquals(1, ifStmt.elseBranch().size());
        assertInstanceOf(Ir.LogStmt.class, ifStmt.thenBranch().get(0));
        assertInstanceOf(Ir.LogStmt.class, ifStmt.elseBranch().get(0));
        assertEquals(1, body.size(),
                "branches consume their own tails; nothing follows the if in the outer chain");
    }
}


