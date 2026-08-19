package plugin.processor;

import static com.google.testing.compile.Compiler.javac;
import static com.google.testing.compile.JavaFileObjects.forSourceLines;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ComponentRegistryProcessorTest {

    @Test
    void includesOnlyAnnotatedClasses() throws Exception {
        var compilation = javac()
                .withProcessors(new ComponentRegistryProcessor())
                .compile(
                        forSourceLines("sample.Alpha",
                                "package sample;",
                                "import plugin.annotations.Component;",
                                "@Component public class Alpha {}"),
                        forSourceLines("sample.Beta",
                                "package sample;",
                                "public class Beta {}"),
                        forSourceLines("sample.ComponentThing",
                                "package sample;",
                                "public class ComponentThing {}"),
                        forSourceLines("sample.HasComment",
                                "package sample;",
                                "// @Component this is a comment",
                                "public class HasComment {}"),
                        forSourceLines("sample.MyAnno",
                                "package sample;",
                                "import plugin.annotations.Component;",
                                "@Component public @interface MyAnno {}"));

        assertTrue(compilation.errors().isEmpty(), () -> compilation.errors().toString());

        String content = compilation.generatedSourceFile("plugin.core.ComponentRegistry")
                .get().getCharContent(true).toString();

        assertTrue(content.contains("sample.Alpha.class"));
        assertFalse(content.contains("sample.Beta.class"));
        assertFalse(content.contains("sample.ComponentThing.class"));
        assertFalse(content.contains("sample.HasComment.class"));
        assertFalse(content.contains("sample.MyAnno.class"));
    }

    @Test
    void outputIsSortedByQualifiedName() throws Exception {
        var compilation = javac()
                .withProcessors(new ComponentRegistryProcessor())
                .compile(
                        forSourceLines("zeta.Third",
                                "package zeta;",
                                "import plugin.annotations.Component;",
                                "@Component public class Third {}"),
                        forSourceLines("alpha.First",
                                "package alpha;",
                                "import plugin.annotations.Component;",
                                "@Component public class First {}"),
                        forSourceLines("beta.Second",
                                "package beta;",
                                "import plugin.annotations.Component;",
                                "@Component public class Second {}"));

        assertTrue(compilation.errors().isEmpty(), () -> compilation.errors().toString());

        String content = compilation.generatedSourceFile("plugin.core.ComponentRegistry")
                .get().getCharContent(true).toString();

        int alpha = content.indexOf("alpha.First.class");
        int beta = content.indexOf("beta.Second.class");
        int zeta = content.indexOf("zeta.Third.class");

        assertTrue(alpha >= 0 && beta > alpha && zeta > beta, "expected lexicographic sort by fully-qualified name");
    }

    @Test
    void generatedRegistryCompiles() {
        var compilation = javac()
                .withProcessors(new ComponentRegistryProcessor())
                .compile(
                        forSourceLines("sample.Alpha",
                                "package sample;",
                                "import plugin.annotations.Component;",
                                "@Component public class Alpha {}"));

        assertTrue(compilation.errors().isEmpty(), () -> compilation.errors().toString());
        assertTrue(compilation.generatedSourceFile("plugin.core.ComponentRegistry").isPresent());
    }
}