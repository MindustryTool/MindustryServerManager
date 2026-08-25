package graph.compile;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import graph.compile.TypeChecker.CheckResult;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypeCheckerTest {

    private GraphRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new GraphRegistry();
        registry.register(FunctionDescriptor.builder("mindustry.player.sendMessage")
                .overload(Overload.of(TypeRef.BOOLEAN,
                        new ParamDescriptor("player", TypeRef.of("Player")),
                        new ParamDescriptor("message", TypeRef.STRING)))
                .build(), (hash, args, ctx) -> true);
        registry.register(FunctionDescriptor.builder("math.double")
                .overload(Overload.of(TypeRef.INT, new ParamDescriptor("v", TypeRef.INT)))
                .build(), (hash, args, ctx) -> null);
        registry.register(new EventDescriptor("mindustry.event.player.join", "Join",
                List.of(new ParamDescriptor("player", TypeRef.of("Player"))), "", ""));
    }

    private record NodeSpec(String json) {
        GraphNode node() throws Exception {
            var tree = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json());
            return GraphNode.of(tree.get("id").asText(), tree.get("type").asText(), toMap(tree));
        }

        private static java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> toMap(
                com.fasterxml.jackson.databind.JsonNode tree) {
            var data = new java.util.LinkedHashMap<String, com.fasterxml.jackson.databind.JsonNode>();
            tree.fields().forEachRemaining(e -> {
                if (!e.getKey().equals("id") && !e.getKey().equals("type")) {
                    data.put(e.getKey(), e.getValue());
                }
            });
            return data;
        }
    }

    private static NodeSpec n(String json) {
        return new NodeSpec(json);
    }

    private CheckResult check(String nodeJson, String... edgePairs) throws Exception {
        String wrapped = "[" + nodeJson.strip().replaceFirst("^\\[", "").replaceFirst("\\]$", "")
                .replace("}\n{", "},\n{") + "]";
        var array = new com.fasterxml.jackson.databind.ObjectMapper().readTree(wrapped);
        if (!array.isArray()) {
            throw new IllegalStateException("node json must be object or array: " + wrapped);
        }
        List<GraphNode> nodes = new java.util.ArrayList<>();
        for (var element : array) {
            nodes.add(GraphNode.of(element.get("id").asText(), element.get("type").asText(),
                    NodeSpec.toMap(element)));
        }
        List<GraphEdge> edges = new java.util.ArrayList<>();
        for (String pair : edgePairs) {
            String[] parts = pair.split("->");
            edges.add(GraphEdge.of(parts[0].trim(), parts[1].trim()));
        }
        GraphDocument doc = GraphDocument.initial("t", List.of(), nodes, edges, null);
        LinkedGraph linkedGraph = new Linker(registry).link(doc).graph();
        if (linkedGraph == null) {
            throw new IllegalStateException("linking failed before type check");
        }
        return TypeChecker.check(doc, linkedGraph);
    }

    @Test
    void validEventToCallGraphPasses() throws Exception {
        CheckResult result = check("""
                {"id":"ev","type":"event","event":"mindustry.event.player.join"}
                {"id":"say","type":"call","function":"mindustry.player.sendMessage",
                 "args":{"message":{"kind":"literal","value":"Welcome"}}}
                """,
                "ev.player -> say.player",
                "say.then -> ev.then");
        assertTrue(result.result().ok(),
                () -> result.result().diagnostics().toString());
        assertEquals(TypeRef.of("Player"), result.inferredPorts().get("ev.player"));
    }

    @Test
    void typeMismatchRejectedWithPorts() throws Exception {
        CheckResult result = check("""
                {"id":"ev","type":"event","event":"mindustry.event.player.join"}
                {"id":"dbl","type":"call","function":"math.double"}
                """,
                "ev.player -> dbl.v");
        ValidationResult validation = result.result();
        assertFalse(validation.ok());
        assertTrue(validation.diagnostics().stream().anyMatch(d ->
                d.code().equals("E_TYPE_MISMATCH")
                        && d.message().contains("Player")
                        && d.message().contains("Int")));
    }

    @Test
    void missingCallInputRejectedWhenNoLiteralAndNoEdge() throws Exception {
        CheckResult result = check("""
                {"id":"say","type":"call","function":"mindustry.player.sendMessage"}
                """);
        assertFalse(result.result().ok());
        assertTrue(result.result().diagnostics().stream().anyMatch(d ->
                d.code().equals("E_MISSING_INPUT") && d.message().contains("'player'")));
        assertTrue(result.result().diagnostics().stream().anyMatch(d ->
                d.code().equals("E_MISSING_INPUT") && d.message().contains("'message'")));
    }

    @Test
    void literalSatisfiesInputAndIsTypeChecked() throws Exception {
        CheckResult okResult = check("""
                {"id":"say","type":"call","function":"mindustry.player.sendMessage",
                 "args":{"player":null,"message":{"kind":"literal","value":"hi"}}}
                """);
        assertTrue(okResult.result().diagnostics().stream()
                .noneMatch(d -> d.code().equals("E_MISSING_INPUT")
                        && d.message().contains("'message'")));

        CheckResult badLiteral = check("""
                {"id":"dbl","type":"call","function":"math.double",
                 "args":{"v":{"kind":"literal","value":true}}}
                """);
        assertTrue(badLiteral.result().diagnostics().stream().anyMatch(d ->
                d.code().equals("E_TYPE_MISMATCH") && d.message().contains("Literal value")),
                () -> badLiteral.result().diagnostics().toString());
    }

    @Test
    void wideningConnectionAccepted() throws Exception {
        CheckResult result = check("""
                {"id":"src","type":"code","inputs":[{"name":"x","type":"Int"}],
                 "outputs":[{"name":"out","type":"Int"}]}
                {"id":"sink","type":"code","inputs":[{"name":"x","type":"Double"}],
                 "outputs":[]}
                """,
                "src.out -> sink.x");
        assertTrue(result.result().ok(), () -> result.result().diagnostics().toString());
    }

    @Test
    void forEachInfersElementTypeFromListSource() throws Exception {
        registry.register(FunctionDescriptor.builder("players.asList")
                .overload(Overload.of(TypeRef.list(TypeRef.of("Player"))))
                .build(), (hash, args, ctx) -> null);

        CheckResult result = check("""
                {"id":"all","type":"call","function":"players.asList"}
                {"id":"loop","type":"for-each"}
                {"id":"say","type":"call","function":"mindustry.player.sendMessage",
                 "args":{"message":{"kind":"literal","value":"hey"}}}
                """,
                "all.then -> loop.exec",
                "all.result -> loop.list",
                "loop.item -> say.player",
                "loop.body -> say.exec");
        assertTrue(result.result().ok(), () -> result.result().diagnostics().toString());
        assertEquals(TypeRef.of("Player"), result.inferredPorts().get("loop.item"));
    }

    @Test
    void awaitInfersValueFromFuture() throws Exception {
        registry.register(FunctionDescriptor.builder("net.fetchFuture")
                .threadRequirement(graph.registry.ThreadRequirement.ASYNC)
                .overload(Overload.of(TypeRef.future(TypeRef.STRING),
                        new ParamDescriptor("url", TypeRef.STRING)))
                .build(), (hash, args, ctx) -> null);

        CheckResult result = check("""
                {"id":"fetch","type":"call","function":"net.fetchFuture",
                 "args":{"url":{"kind":"literal","value":"https://example.test"}}}
                {"id":"wait","type":"await"}
                {"id":"len","type":"log"}
                """,
                "fetch.result -> wait.future",
                "wait.value -> len.message");
        assertTrue(result.result().ok(), () -> result.result().diagnostics().toString());
        assertEquals(TypeRef.STRING, result.inferredPorts().get("wait.value"));
    }

    @Test
    void execDataConfusionRejected() throws Exception {
        CheckResult result = check("""
                {"id":"ev","type":"event","event":"mindustry.event.player.join"}
                {"id":"say","type":"call","function":"mindustry.player.sendMessage",
                 "args":{"message":{"kind":"literal","value":"hi"}}}
                """,
                "say.then -> ev.player");
        assertFalse(result.result().ok());
        assertTrue(result.result().diagnostics().stream()
                .anyMatch(d -> d.code().equals("E_EXEC_MISMATCH")));
    }

    @Test
    void nullableSourceIntoStrictTargetRejected() throws Exception {
        registry.register(FunctionDescriptor.builder("find.player")
                .overload(Overload.of(TypeRef.of("Player").asNullable(),
                        new ParamDescriptor("name", TypeRef.STRING)))
                .build(), (hash, args, ctx) -> null);
        registry.register(FunctionDescriptor.builder("use.strictPlayer")
                .overload(Overload.of(TypeRef.BOOLEAN,
                        new ParamDescriptor("p", TypeRef.of("Player"))))
                .build(), (hash, args, ctx) -> null);

        CheckResult result = check("""
                {"id":"f","type":"call","function":"find.player"}
                {"id":"u","type":"call","function":"use.strictPlayer"}
                """,
                "f.result -> u.p");
        assertFalse(result.result().ok());
        assertTrue(result.result().diagnostics().stream()
                .anyMatch(d -> d.code().equals("E_TYPE_MISMATCH")
                        && d.message().contains("nullable") == false
                        && d.message().contains("Player?")));
    }
}

