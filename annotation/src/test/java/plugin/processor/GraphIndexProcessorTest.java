package plugin.processor;

import static com.google.testing.compile.Compiler.javac;
import static com.google.testing.compile.JavaFileObjects.forSourceLines;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GraphIndexProcessorTest {

    private static javax.tools.JavaFileObject[] toArray(Object... sources) {
        javax.tools.JavaFileObject[] files = new javax.tools.JavaFileObject[sources.length];
        for (int i = 0; i < sources.length; i++) {
            files[i] = (javax.tools.JavaFileObject) sources[i];
        }
        return files;
    }

    private static com.google.testing.compile.Compilation compile(Object... sources) {
        return javac().withProcessors(new GraphIndexProcessor()).compile(toArray(sources));
    }

    @Test
    void generatesFunctionColumns() throws Exception {
        var compilation = compile(
                forSourceLines("sample.Facades",
                        "package sample;",
                        "import plugin.annotations.GraphCategory;",
                        "import plugin.annotations.GraphFunction;",
                        "@GraphCategory(name = \"Communication\")",
                        "public class Facades {",
                        "  @GraphFunction(id = \"player.sendMessage\", aliases = {\"say\", \"msg\"},",
                        "      threadReq = \"MAIN_THREAD\")",
                        "  public static void sendMessage(java.util.UUID player, String text) {}",
                        "",
                        "  @GraphFunction(id = \"net.fetch\", category = \"Network\",",
                        "      threadReq = \"ASYNC\", advanced = true, description = \"Fetches a URL\")",
                        "  public static String fetch(String url) { return null; }",
                        "}"));

        assertTrue(compilation.errors().isEmpty(), () -> compilation.errors().toString());

        String content = compilation.generatedSourceFile("plugin.core.GeneratedGraphIndex")
                .get().getCharContent(true).toString();

        assertTrue(content.contains("\"player.sendMessage\""));
        assertTrue(content.contains("\"net.fetch\""));
        assertTrue(content.contains("FUNCTION_IDS"));
        assertTrue(content.contains("\"say|msg\""));
        assertTrue(content.contains("\"ASYNC\""));
        assertTrue(content.contains("\"MAIN_THREAD\""));
        assertTrue(content.contains("\"true\""));
        assertTrue(content.contains("\"Communication\""));
        assertTrue(content.contains("\"Network\""));
        assertTrue(content.contains("java.util.UUID,java.lang.String"), () -> content);
        assertTrue(content.contains("\"void\""));
        assertTrue(content.contains("\"java.lang.String\""));
    }

    @Test
    void generatesEventPropertyConstructorColumns() throws Exception {
        var compilation = compile(
                forSourceLines("sample.Facades2",
                        "package sample;",
                        "import plugin.annotations.GraphConstructor;",
                        "import plugin.annotations.GraphEvent;",
                        "import plugin.annotations.GraphProperty;",
                        "public class Facades2 {",
                        "  @GraphEvent(id = \"player.join\", event = sample.JoinEvent.class)",
                        "  public static void onJoin(sample.JoinEvent event) {}",
                        "",
                        "  @GraphProperty(id = \"player.team\", property = \"team\", writable = true)",
                        "  public static String teamOf(String player) { return null; }",
                        "",
                        "  @GraphConstructor(id = \"team.construct\", description = \"New team\")",
                        "  public static String newTeam(String name) { return null; }",
                        "}"),
                forSourceLines("sample.JoinEvent",
                        "package sample;",
                        "public class JoinEvent {}"));

        assertTrue(compilation.errors().isEmpty(), () -> compilation.errors().toString());

        String content = compilation.generatedSourceFile("plugin.core.GeneratedGraphIndex")
                .get().getCharContent(true).toString();

        assertTrue(content.contains("\"player.join\""));
        assertTrue(content.contains("\"sample.JoinEvent\""));
        assertTrue(content.contains("\"player.team\""));
        assertTrue(content.contains("\"team\""));
        assertTrue(content.contains("\"true\""));
        assertTrue(content.contains("\"team.construct\""));
        assertTrue(content.contains("\"New team\""));
        assertFalse(content.contains("FUNCTION_IDS"));
    }

    @Test
    void duplicateIdsFailCompilation() {
        var compilation = compile(
                forSourceLines("sample.Facades3",
                        "package sample;",
                        "import plugin.annotations.GraphFunction;",
                        "public class Facades3 {",
                        "  @GraphFunction(id = \"same.id\")",
                        "  public static void a() {}",
                        "",
                        "  @GraphFunction(id = \"same.id\")",
                        "  public static void b() {}",
                        "}"));

        assertTrue(compilation.errors().stream()
                .anyMatch(d -> d.getMessage(null).contains("Duplicate graph registry id 'same.id'")));
    }

    @Test
    void idsAreSorted() throws Exception {
        var compilation = compile(
                forSourceLines("sample.Facades4",
                        "package sample;",
                        "import plugin.annotations.GraphFunction;",
                        "public class Facades4 {",
                        "  @GraphFunction(id = \"zeta.fn\") public static void z() {}",
                        "  @GraphFunction(id = \"alpha.fn\") public static void a() {}",
                        "}"));

        assertTrue(compilation.errors().isEmpty(), () -> compilation.errors().toString());

        String content = compilation.generatedSourceFile("plugin.core.GeneratedGraphIndex")
                .get().getCharContent(true).toString();

        int alpha = content.indexOf("\"alpha.fn\"");
        int zeta = content.indexOf("\"zeta.fn\"");
        assertTrue(alpha >= 0 && zeta > alpha, "expected lexicographic id ordering");
    }
}
