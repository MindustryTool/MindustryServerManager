package plugin.processor;

import static com.google.testing.compile.Compiler.javac;
import static com.google.testing.compile.JavaFileObjects.forSourceLines;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.testing.compile.Compilation;
import org.junit.jupiter.api.Test;

class ComponentRegistryProcessorTest {

    private static final String OFF = String.join("\n",
            "package sample;",
            "import plugin.annotations.Condition;",
            "public class Off implements Condition {",
            "  public boolean check() { return true; }",
            "}");

    private static final String SERVICE_OFF = String.join("\n",
            "package sample;",
            "import plugin.annotations.Component;",
            "import plugin.annotations.ConditionOn;",
            "@Component @ConditionOn(Off.class) public class Service {}");

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

    @Test
    void coveredConstructorEdgePasses() {
        var compilation = javac()
                .withProcessors(new ComponentRegistryProcessor())
                .compile(
                        forSourceLines("sample.Off", OFF.lines().toArray(String[]::new)),
                        forSourceLines("sample.Service", SERVICE_OFF.lines().toArray(String[]::new)),
                        forSourceLines("sample.Dep",
                                "package sample;",
                                "import plugin.annotations.Component;",
                                "import plugin.annotations.ConditionOn;",
                                "@Component @ConditionOn(Off.class) public class Dep {",
                                "  public Dep(Service svc) {}",
                                "}"));

        assertTrue(compilation.status() == Compilation.Status.SUCCESS, () -> compilation.errors().toString());
    }

    @Test
    void uncoveredConstructorEdgeFails() {
        var compilation = javac()
                .withProcessors(new ComponentRegistryProcessor())
                .compile(
                        forSourceLines("sample.Off", OFF.lines().toArray(String[]::new)),
                        forSourceLines("sample.Service", SERVICE_OFF.lines().toArray(String[]::new)),
                        forSourceLines("sample.Dep",
                                "package sample;",
                                "import plugin.annotations.Component;",
                                "@Component public class Dep {",
                                "  public Dep(Service svc) {}",
                                "}"));

        assertTrue(compilation.status() == Compilation.Status.FAILURE);
        assertTrue(compilation.errors().stream().anyMatch(d -> d.getMessage(null).contains("sample.Dep")
                && d.getMessage(null).contains("sample.Service")
                && d.getMessage(null).contains("Off")));
    }

    @Test
    void extraConditionCoveragePasses() {
        var compilation = javac()
                .withProcessors(new ComponentRegistryProcessor())
                .compile(
                        forSourceLines("sample.Off", OFF.lines().toArray(String[]::new)),
                        forSourceLines("sample.On",
                                "package sample;",
                                "import plugin.annotations.Condition;",
                                "public class On implements Condition {",
                                "  public boolean check() { return true; }",
                                "}"),
                        forSourceLines("sample.Service", SERVICE_OFF.lines().toArray(String[]::new)),
                        forSourceLines("sample.Dep",
                                "package sample;",
                                "import plugin.annotations.Component;",
                                "import plugin.annotations.ConditionOn;",
                                "@Component @ConditionOn(Off.class) @ConditionOn(On.class) public class Dep {",
                                "  public Dep(Service svc) {}",
                                "}"));

        assertTrue(compilation.status() == Compilation.Status.SUCCESS, () -> compilation.errors().toString());
    }

    @Test
    void differingArgsFail() {
        var compilation = javac()
                .withProcessors(new ComponentRegistryProcessor())
                .compile(
                        forSourceLines("sample.Mode",
                                "package sample;",
                                "import plugin.annotations.Condition;",
                                "public class Mode implements Condition {",
                                "  public Mode(String[] args) {}",
                                "  public boolean check() { return true; }",
                                "}"),
                        forSourceLines("sample.Service",
                                "package sample;",
                                "import plugin.annotations.Component;",
                                "import plugin.annotations.ConditionOn;",
                                "@Component @ConditionOn(value = Mode.class, args = {\"flood\"}) public class Service {}"),
                        forSourceLines("sample.Dep",
                                "package sample;",
                                "import plugin.annotations.Component;",
                                "import plugin.annotations.ConditionOn;",
                                "@Component @ConditionOn(value = Mode.class, args = {\"catali\"}) public class Dep {",
                                "  public Dep(Service svc) {}",
                                "}"));

        assertTrue(compilation.status() == Compilation.Status.FAILURE);
    }

    @Test
    void differingConditionKindsFail() {
        var compilation = javac()
                .withProcessors(new ComponentRegistryProcessor())
                .compile(
                        forSourceLines("sample.Off", OFF.lines().toArray(String[]::new)),
                        forSourceLines("sample.Service", SERVICE_OFF.lines().toArray(String[]::new)),
                        forSourceLines("sample.Dep",
                                "package sample;",
                                "import plugin.annotations.Component;",
                                "import plugin.annotations.ConditionOn;",
                                "@Component @ConditionOn(sample.Off2.class) public class Dep {",
                                "  public Dep(Service svc) {}",
                                "}"),
                        forSourceLines("sample.Off2",
                                "package sample;",
                                "import plugin.annotations.Condition;",
                                "public class Off2 implements Condition {",
                                "  public boolean check() { return true; }",
                                "}"));

        assertTrue(compilation.status() == Compilation.Status.FAILURE);
    }

    @Test
    void uncoveredCommandMethodParamFails() {
        var compilation = javac()
                .withProcessors(new ComponentRegistryProcessor())
                .compile(
                        forSourceLines("sample.Off", OFF.lines().toArray(String[]::new)),
                        forSourceLines("sample.Service", SERVICE_OFF.lines().toArray(String[]::new)),
                        forSourceLines("sample.Dep",
                                "package sample;",
                                "import plugin.annotations.ClientCommand;",
                                "import plugin.annotations.Component;",
                                "@Component public class Dep {",
                                "  @ClientCommand(name = \"dep\", description = \"dep\")",
                                "  public void dep(Service svc) {}",
                                "}"));

        assertTrue(compilation.status() == Compilation.Status.FAILURE);
        assertTrue(compilation.errors().stream().anyMatch(d -> d.getMessage(null).contains("sample.Dep")
                && d.getMessage(null).contains("sample.Service")));
    }

    @Test
    void nonComponentDependencyIsIgnored() {
        var compilation = javac()
                .withProcessors(new ComponentRegistryProcessor())
                .compile(
                        forSourceLines("sample.Plain",
                                "package sample;",
                                "public class Plain {}"),
                        forSourceLines("sample.Dep",
                                "package sample;",
                                "import plugin.annotations.Component;",
                                "@Component public class Dep {",
                                "  public Dep(Plain plain) {}",
                                "  public Object lookup() { return plugin.core.Registry.getOrNull(Plain.class); }",
                                "}"));

        assertTrue(compilation.status() == Compilation.Status.SUCCESS, () -> compilation.errors().toString());
    }
}
