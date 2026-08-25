package graph.compile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import graph.compile.CompilerService.CompiledClasses;
import graph.runtime.GraphExecutable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CacheAndLoaderTest {

    @TempDir
    Path tempDir;

    private static Map<String, byte[]> sampleClasses() {
        Map<String, byte[]> classes = new HashMap<>();
        classes.put("graph.generated.Graph_demo", new byte[]{1, 2, 3, 4});
        classes.put("graph.generated.Graph_demo$Inner", new byte[]{5, 6});
        return classes;
    }

    private static SourceMap sampleMap() {
        return new SourceMap("demo", "Graph_demo", List.of(
                new SourceMap.Mapping(10, 12, "ev", null),
                new SourceMap.Mapping(14, 14, "say", "mindustry.player.sendMessage")));
    }

    @Test
    void storeLoadRoundTripPreservesClassesAndSourceMap() throws Exception {
        GraphArtifactCache cache = new GraphArtifactCache(tempDir.resolve("cache"));
        String key = GraphArtifactCache.cacheKey("doc-hash", "tool-provider-17", 1, 1,
                "fp123");
        cache.store(key, sampleClasses(), sampleMap());

        assertTrue(cache.contains(key));
        Map<String, byte[]> loaded = cache.load(key).orElseThrow();
        assertEquals(2, loaded.size());
        assertEquals(4, loaded.get("graph.generated.Graph_demo").length);
        assertEquals(2, loaded.get("graph.generated.Graph_demo$Inner").length);

        String mapJson = cache.loadSourceMap(key).orElseThrow();
        assertTrue(mapJson.contains("\"nodeId\":\"ev\""));
        assertTrue(mapJson.contains("\"functionId\":\"mindustry.player.sendMessage\""));
    }

    @Test
    void missingEntryIsEmptyNotError() {
        GraphArtifactCache cache = new GraphArtifactCache(tempDir.resolve("cache"));
        assertTrue(cache.load("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa").isEmpty());
        assertTrue(cache.loadSourceMap("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa").isEmpty());
        assertFalse(cache.contains("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
    }

    @Test
    void keyIsSensitiveToEveryComponent() {
        String base = GraphArtifactCache.cacheKey("d", "c", 1, 1, "f");
        assertEquals(base, GraphArtifactCache.cacheKey("d", "c", 1, 1, "f"),
                "identical inputs must be stable");
        assertThrows(AssertionError.class, () -> {
            if (!GraphArtifactCache.cacheKey("d2", "c", 1, 1, "f").equals(base)
                    && !GraphArtifactCache.cacheKey("d", "c2", 1, 1, "f").equals(base)) {
                throw new AssertionError();
            }
        });
        assertFalse(GraphArtifactCache.cacheKey("d", "c", 1, 1, "f2").equals(base));
        assertFalse(GraphArtifactCache.cacheKey("d", "c", 2, 1, "f").equals(base));
        assertFalse(GraphArtifactCache.cacheKey("d", "c", 1, 2, "f").equals(base));
        assertFalse(GraphArtifactCache.cacheKey("d2", "c", 1, 1, "f").equals(base));
        assertFalse(GraphArtifactCache.cacheKey("d", "c2", 1, 1, "f").equals(base));
    }

    @Test
    void invalidKeyShapeRejected() {
        GraphArtifactCache cache = new GraphArtifactCache(tempDir.resolve("cache"));
        assertThrows(IllegalArgumentException.class, () -> cache.load("../evil"));
        assertThrows(IllegalArgumentException.class, () -> cache.contains("NOT_HEX"));
    }

    @Test
    void clearSelfHeals() throws Exception {
        GraphArtifactCache cache = new GraphArtifactCache(tempDir.resolve("cache"));
        String key = GraphArtifactCache.cacheKey("h", "c", 1, 1, "f");
        cache.store(key, sampleClasses(), sampleMap());
        assertTrue(cache.remove(key));
        assertFalse(cache.contains(key));
        assertTrue(cache.load(key).isEmpty(), "self-heal: recompile path");
        Files.createDirectories(tempDir);
    }

    @Test
    void generationalLoaderDefinesAndRetires() throws Exception {
        CompilerService service = new CompilerService();
        CompiledClasses compiled = service.compile("graph.generated.Graph_demo", """
                package graph.generated;

                import graph.runtime.*;
                import java.util.Map;
                import java.util.Set;

                public final class Graph_demo implements GraphExecutable {
                    public String graphId() { return "demo"; }
                    public Set<String> eventNodeIds() { return Set.of("ev"); }
                    public void execute(String e, Map<String,Object> p,
                            InvocationContext c, RuntimeServices s) { }
                }
                """);

        GenerationClassLoader loader = new GenerationClassLoader(
                compiled.classes(), GraphExecutable.class.getClassLoader());

        Class<?> type = loader.loadMain("graph.generated.Graph_demo");
        assertEquals(loader, type.getClassLoader());
        Object instance = type.getDeclaredConstructor().newInstance();
        assertEquals("demo", type.getMethod("graphId").invoke(instance));

        assertThrows(ClassNotFoundException.class,
                () -> loader.loadClass("graph.generated.DoesNotExist"));

        loader.retire();
        assertTrue(loader.isRetired());
        IllegalStateException state = assertThrows(IllegalStateException.class,
                () -> loader.findClass("graph.generated.Graph_demo"),
                "retired generation must refuse further definitions");

        Class<?> redefined = new GenerationClassLoader(compiled.classes(),
                GraphExecutable.class.getClassLoader())
                .loadMain("graph.generated.Graph_demo");
        assertEquals("demo",
                redefined.getMethod("graphId").invoke(
                        redefined.getDeclaredConstructor().newInstance()),
                "fresh generation loads cleanly after old one retired");
    }
}
