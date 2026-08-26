package plugin.graph;

import graph.compile.CompilerService;
import graph.compile.ExecutionEngine;
import graph.compile.JavaGenerator;
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
import graph.compile.TypeChecker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeFragmentClasspathAccessTest {

    private GraphRegistry registry;
    private List<Object> collected;
    private final List<String> errors = new ArrayList<>();

    @BeforeEach
    void setUp() {
        collected = new ArrayList<>();
        errors.clear();
        registry = new GraphRegistry();
        registry.register(new EventDescriptor("mindustry.event.player.join", "Join",
                List.of(), "", ""));
        registry.register(FunctionDescriptor.builder("test.collect")
                .overload(Overload.of(TypeRef.BOOLEAN,
                        new ParamDescriptor("value", TypeRef.of("Object"))))
                .build(), (hash, args, ctx) -> {
            collected.add(args[0]);
            return true;
        });
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
        graph.compile.LinkedGraph linked =
                new graph.compile.Linker(registry).link(doc).graph();
        TypeChecker.CheckResult checked = TypeChecker.check(doc, linked);
        graph.compile.ThreadCheckResult threads =
                graph.compile.ThreadCheck.check(doc, linked);
        graph.compile.Ir.IrGraph irGraph =
                new graph.compile.Lowerer(doc, linked, threads,
                        checked.inferredPorts()).lower().ir();
        irGraph = graph.compile.IrOptimizer.optimize(irGraph, threads);
        JavaGenerator.GeneratedSource src =
                new JavaGenerator(doc.id()).generate(irGraph);
        CompilerService.CompiledClasses compiled =
                new CompilerService().compile(src.qualifiedName(), src.source());
        assertTrue(compiled.ok(), () -> String.join("\n", compiled.errors()));
        return new Pipeline(doc, compiled.classes().get(src.qualifiedName()));
    }

    private static final String BODY =
            "java.nio.file.Path tmp = java.nio.file.Files.createTempFile(\"graph-fragment\", \".txt\");\n"
                    + "java.nio.file.Files.writeString(tmp, \"classpath-ok\");\n"
                    + "String back = java.nio.file.Files.readString(tmp);\n"
                    + "output(\"fileLen\", back.length());\n"
                    + "Class<?> time = Class.forName(\"arc.util.Time\");\n"
                    + "output(\"timeDelta\", time.getField(\"delta\").get(null));";

    @Test
    void fragmentUsesFilesApiAndMindustryInternalsFromFullClasspath()
            throws Exception {
        MainThreadDispatcher directMain = new MainThreadDispatcher() {
            @Override public boolean isMainThread() { return true; }
            @Override public void post(Runnable r) { r.run(); }
        };
        ExecutionEngine engine = new ExecutionEngine(directMain, registry,
                error -> errors.add(error.toString()), line -> { }, 10_000, true);
        engine.installScheduler((seconds, continuation) -> () -> { });

        String json = JOIN + "\n"
                + "{\"id\":\"c\",\"type\":\"call\",\"function\":\"test.collect\"}\n"
                + "{\"id\":\"cd\",\"type\":\"code\",\"properties\":{\"body\":"
                + quote(BODY) + "}}";
        Pipeline pipeline = pipeline(json,
                "ev.then -> cd.exec",
                "cd.then -> c.exec",
                "cd.result -> c.value");
        Map<String, byte[]> allClasses = Map.of(
                "graph.generated." + JavaGenerator.className("welcome-flow"),
                pipeline.classes());
        engine.enable("welcome-flow", allClasses,
                GraphExecutable.class.getClassLoader());

        engine.dispatch("welcome-flow", "ev", Map.of());

        assertTrue(errors.isEmpty(), errors.toString());
        assertEquals(1, collected.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> outputs = (Map<String, Object>) collected.get(0);
        assertEquals(12, ((Number) outputs.get("fileLen")).intValue());
        assertInstanceOf(Number.class, outputs.get("timeDelta"));
    }

    private static final String JOIN =
            "{\"id\":\"ev\",\"type\":\"event\",\"event\":\"mindustry.event.player.join\"}";

    private static String quote(String raw) {
        return "\"" + raw
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "")
                + "\"";
    }
}
