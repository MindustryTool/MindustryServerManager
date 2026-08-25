package graph.compile;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import graph.compile.CompilerService.CompiledClasses;
import graph.compile.ExecutionEngine;
import graph.compile.TypeChecker.CheckResult;
import graph.format.GraphDocument;
import graph.format.GraphEdge;
import graph.format.GraphNode;
import graph.registry.EventDescriptor;
import graph.registry.GraphRegistry;
import graph.registry.ParamDescriptor;
import graph.registry.ReflectionRegistryLoader;
import graph.compile.ExecutionEngine;
import graph.runtime.GraphExecutable;
import graph.runtime.MainThreadDispatcher;
import graph.types.TypeRef;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReflectiveEndToEndTest {

    private GraphRegistry registry;
    private List<String> logs;
    private MainThreadDispatcher main;

    @BeforeEach
    void setUp() {
        logs = new ArrayList<>();
        registry = new GraphRegistry();
        registry.register(new EventDescriptor("mindustry.event.player.join", "Join",
                List.of(new ParamDescriptor("player", TypeRef.STRING)), "", ""));

        for (ReflectionRegistryLoader.LoadedEntry entry
                : ReflectionRegistryLoader.loadClass(String.class, "text")) {
            if (!entry.id().equals("java.lang.String.valueOf")) {
                continue;
            }
            registry.register(entry.descriptor(),
                    new ReflectionRegistryLoader.OverloadDispatchInvoker(entry.invokersByHash()));
        }
        main = new MainThreadDispatcher() {
            @Override
            public boolean isMainThread() {
                return true;
            }

            @Override
            public void post(Runnable r) {
                r.run();
            }
        };
    }

    private record Pipeline(Map<String, byte[]> classes, SourceMap sourceMap) {
    }

    private Pipeline pipeline(String nodesJson, String... edges) throws Exception {
        return pipeline(List.of(), nodesJson, edges);
    }

    private Pipeline pipeline(List<graph.format.VariableDecl> variables,
                              String nodesJson, String... edges) throws Exception {
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
            nodes.add(GraphNode.of(element.get("id").asText(),
                    element.get("type").asText(), data));
        }
        List<GraphEdge> edgeList = new ArrayList<>();
        for (String pair : edges) {
            String[] parts = pair.split("->");
            edgeList.add(GraphEdge.of(parts[0].trim(), parts[1].trim()));
        }
        GraphDocument doc = GraphDocument.initial("refl-flow", variables, nodes,
                edgeList, null);
        Linker.LinkResult linkResult = new Linker(registry).link(doc);
        if (!linkResult.ok()) {
            throw new IllegalStateException("linking failed: "
                    + linkResult.diagnostics());
        }
        LinkedGraph linked = linkResult.graph();
        CheckResult checked = TypeChecker.check(doc, linked);
        ThreadCheckResult threads = ThreadCheck.check(doc, linked);
        Ir.IrGraph irGraph = new Lowerer(doc, linked, threads,
                checked.inferredPorts()).lower().ir();
        irGraph = IrOptimizer.optimize(irGraph, threads);
        JavaGenerator.GeneratedSource src = new JavaGenerator(doc.id()).generate(irGraph);
        CompiledClasses compiled = new CompilerService()
                .compile(src.qualifiedName(), src.source());
        assertTrue(compiled.ok(), () -> String.join("\n", compiled.errors()));
        return new Pipeline(compiled.classes(), src.sourceMap());
    }

    private static final String JOIN_NODE =
            "{\"id\":\"ev\",\"type\":\"event\",\"event\":\"mindustry.event.player.join\"}";

    @Test
    void reflectedJdkMethodRunsThroughCompiledGraphAndStoresResult() throws Exception {
        var descriptor = registry.function("java.lang.String.valueOf");
        String hash = descriptor.overloads().stream()
                .filter(o -> o.signature().equals("(Int):String"))
                .findFirst().orElseThrow().hash();

        Pipeline pipeline = pipeline(
                List.of(new graph.format.VariableDecl("lastResult", "SERVER", "String")),
                JOIN_NODE + "\n" + """
                {"id":"conv","type":"call","function":"java.lang.String.valueOf",
                 "overload":"%s",
                 "args":{"arg0":{"kind":"literal","value":1234}}}
                {"id":"save","type":"set-variable","variable":"lastResult","scope":"SERVER"}
                """.formatted(hash),
                "ev.then -> conv.exec",
                "conv.then -> save.exec",
                "conv.result -> save.value");

        ExecutionEngine engine = new ExecutionEngine(main, registry,
                errors -> { }, logs::add, 10_000, true);
        engine.enable("refl-flow", pipeline.classes(),
                GraphExecutable.class.getClassLoader(), pipeline.sourceMap());

        engine.dispatch("refl-flow", "ev", Map.of("player", "Alice"));

        assertEquals("1234", engine.serverVariable("lastResult"),
                "reflected String.valueOf(int) must run through the compiled graph"
                        + " and its result must land in SERVER scope");
        assertTrue(logs.stream().noneMatch(l -> l.contains("failed")),
                () -> logs.toString());
    }

    @Test
    void wrongOverloadHashRejectedAtLinkTime() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var nodeJson = mapper.readTree("""
                {"id":"conv","type":"call",
                 "function":"java.lang.String.valueOf",
                 "overload":"000000000000"}
                """);
        var data = new java.util.LinkedHashMap<String, com.fasterxml.jackson.databind.JsonNode>();
        nodeJson.fields().forEachRemaining(e -> {
            if (!e.getKey().equals("id") && !e.getKey().equals("type")) {
                data.put(e.getKey(), e.getValue());
            }
        });
        Linker.LinkResult linkResult = new Linker(registry).link(
                GraphDocument.initial("refl-flow", List.of(),
                        List.of(GraphNode.of("conv", "call", data)),
                        List.of(), null));

        assertFalse(linkResult.ok());
        assertTrue(linkResult.diagnostics().stream()
                .anyMatch(d -> d.code().equals("E_OVERLOAD_NOT_FOUND")),
                () -> linkResult.diagnostics().toString());
    }
}

