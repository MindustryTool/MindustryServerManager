package graph.compile;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import graph.compile.Linker.LinkResult;
import graph.format.Diagnostic;
import graph.format.GraphDocument;
import graph.format.GraphNode;
import graph.registry.EventDescriptor;
import graph.registry.FunctionDescriptor;
import graph.registry.GraphRegistry;
import graph.registry.Overload;
import graph.registry.ParamDescriptor;
import graph.registry.PropertyDescriptor;
import graph.types.TypeRef;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LinkerTest {

    private GraphRegistry registry;
    private Linker linker;

    @BeforeEach
    void setUp() {
        registry = new GraphRegistry();
        registry.register(FunctionDescriptor.builder("mindustry.player.sendMessage")
                .overload(Overload.of(TypeRef.BOOLEAN,
                        new ParamDescriptor("player", TypeRef.of("Player")),
                        new ParamDescriptor("message", TypeRef.STRING)))
                .build(), (hash, args, ctx) -> true);
        registry.register(FunctionDescriptor.builder("math.convert")
                .overload(Overload.of(TypeRef.STRING, new ParamDescriptor("v", TypeRef.INT)))
                .overload(Overload.of(TypeRef.INT, new ParamDescriptor("v", TypeRef.STRING)))
                .build(), (hash, args, ctx) -> null);
        registry.register(new EventDescriptor("mindustry.event.player.join", "Player Join",
                List.of(new ParamDescriptor("player", TypeRef.of("Player"))), "", ""));
        registry.register(new PropertyDescriptor("mindustry.player.team", "team",
                TypeRef.of("Player"), TypeRef.of("Team"), true,
                graph.registry.ThreadRequirement.MAIN_THREAD, ""));
        linker = new Linker(registry);
    }

    private GraphDocument doc(GraphNode... nodes) {
        return GraphDocument.initial("flow-test", List.of(), List.of(nodes), List.of(), null);
    }

    private static final String JSON = "{\"k\":\"v\"}";

    private static GraphNode node(String id, String type) {
        return GraphNode.of(id, type);
    }

    @Test
    void linksSingleOverloadCallAutomatically() {
        GraphDocument document = doc(
                withJson(GraphNode.of("ev", "event"), "event", "\"mindustry.event.player.join\""),
                withJson(GraphNode.of("say", "call"), "function",
                        "\"mindustry.player.sendMessage\""));
        LinkResult result = linker.link(document);
        assertTrue(result.ok(), () -> result.diagnostics().toString());
        LinkedGraph.LinkedNode say = result.graph().node("say");
        assertNotNull(say.overload());
        assertEquals(2, say.overload().arity());
        assertEquals(2, result.graph().consumedIds().size());
        assertFalse(result.graph().registryFingerprint().isEmpty());
    }

    private static GraphNode withJson(GraphNode target, String field, String rawJson) {
        try {
            var tree = new com.fasterxml.jackson.databind.ObjectMapper().readTree(rawJson);
            var data = new java.util.LinkedHashMap<String, com.fasterxml.jackson.databind.JsonNode>();
            target.data().forEach(data::put);
            data.put(field, tree);
            return GraphNode.of(target.id(), target.type(), data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void ambiguousOverloadRejectedWithoutHash() throws Exception {
        GraphNode call = withJson(GraphNode.of("c", "call"), "function", "\"math.convert\"");
        LinkResult result = linker.link(doc(call));
        assertFalse(result.ok());
        assertTrue(result.diagnostics().stream()
                .anyMatch(d -> d.code().equals("E_AMBIGUOUS_OVERLOAD") && d.nodeId().equals("c")));
        assertNull(result.graph());
    }

    @Test
    void overloadHashSelectsExactOverload() throws Exception {
        Overload stringToInt = Overload.of(TypeRef.INT, new ParamDescriptor("v", TypeRef.STRING));
        GraphNode call = withJson(withJson(GraphNode.of("c", "call"), "function",
                "\"math.convert\""), "overload",
                "\"" + stringToInt.hash() + "\"");
        LinkResult result = linker.link(doc(call));
        assertTrue(result.ok(), () -> result.diagnostics().toString());
        assertEquals(stringToInt, result.graph().node("c").overload());
    }

    @Test
    void unknownOverloadHashRejected() throws Exception {
        GraphNode call = withJson(withJson(GraphNode.of("c", "call"), "function",
                "\"mindustry.player.sendMessage\""), "overload", "\"deadbeef0000\"");
        LinkResult result = linker.link(doc(call));
        assertFalse(result.ok());
        assertTrue(result.diagnostics().stream()
                .anyMatch(d -> d.code().equals("E_OVERLOAD_NOT_FOUND")));
    }

    @Test
    void unknownFunctionEventPropertyReportedWithNodeId() {
        LinkResult r1 = linker.link(doc(withJson(GraphNode.of("x", "call"),
                "function", "\"no.such.fn\"")));
        assertTrue(r1.diagnostics().stream().anyMatch(d ->
                d.code().equals("E_UNKNOWN_FUNCTION") && d.nodeId().equals("x")));

        LinkResult r2 = linker.link(doc(withJson(GraphNode.of("y", "event"),
                "event", "\"no.such.event\"")));
        assertTrue(r2.diagnostics().stream().anyMatch(d ->
                d.code().equals("E_UNKNOWN_EVENT") && d.nodeId().equals("y")));

        LinkResult r3 = linker.link(doc(withJson(GraphNode.of("z", "get-property"),
                "property", "\"no.such.prop\"")));
        assertTrue(r3.diagnostics().stream().anyMatch(d ->
                d.code().equals("E_UNKNOWN_PROPERTY") && d.nodeId().equals("z")));
    }

    @Test
    void readOnlyPropertyCannotBeSet() throws Exception {
        registry.register(new PropertyDescriptor("p.ro", "ro", TypeRef.of("Player"),
                TypeRef.INT, false, graph.registry.ThreadRequirement.MAIN_THREAD, ""));
        LinkResult result = linker.link(doc(withJson(GraphNode.of("s", "set-property"),
                "property", "\"p.ro\"")));
        assertFalse(result.ok());
        assertTrue(result.diagnostics().stream()
                .anyMatch(d -> d.code().equals("E_PROPERTY_READ_ONLY")));

        LinkResult getOk = linker.link(doc(withJson(GraphNode.of("g", "get-property"),
                "property", "\"p.ro\"")));
        assertTrue(getOk.ok());
    }

    @Test
    void variableNodesRequireDeclaredVariable() throws Exception {
        GraphDocument withVar = GraphDocument.initial("flow-test",
                List.of(new graph.format.VariableDecl("count", "SERVER", "Int")),
                List.of(withJson(GraphNode.of("get", "get-variable"), "variable", "\"count\"")),
                List.of(), null);
        assertTrue(linker.link(withVar).ok());

        GraphDocument missingVar = GraphDocument.initial("flow-test",
                List.of(),
                List.of(withJson(GraphNode.of("get", "get-variable"), "variable", "\"ghost\"")),
                List.of(), null);
        LinkResult result = linker.link(missingVar);
        assertFalse(result.ok());
        assertTrue(result.diagnostics().stream()
                .anyMatch(d -> d.code().equals("E_UNKNOWN_VARIABLE")));
    }

    @Test
    void plainNodesPassThroughUnlinked() {
        LinkResult result = linker.link(doc(node("d", "delay"), node("log", "log")));
        assertTrue(result.ok());
        assertEquals("delay", result.graph().node("d").type());
        assertNull(result.graph().node("d").function());
        assertTrue(result.graph().consumedIds().isEmpty());
        assertFalse(result.graph().registryFingerprint().isEmpty(),
                "empty consumed set still yields the empty-input SHA-256 fingerprint");
    }
}


