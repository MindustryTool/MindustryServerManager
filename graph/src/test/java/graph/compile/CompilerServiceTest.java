package graph.compile;

import org.junit.jupiter.api.Test;

import graph.compile.CompilerService.CompiledClasses;
import graph.compile.CompilerService.Engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompilerServiceTest {

    private final CompilerService service = new CompilerService();

    @Test
    void engineDetected() {
        Engine engine = service.availableEngine();
        assertTrue(engine == Engine.SYSTEM_TOOL_PROVIDER || engine == Engine.ECJ_FALLBACK,
                "expected a usable or declared-fallback engine, got NONE semantics");
    }

    @Test
    void compilesSimpleClassIntoMemory() {
        CompiledClasses result = service.compile("sample.Hello", """
                package sample;

                public final class Hello {
                    public String greet() { return "hi"; }
                }
                """);

        if (service.availableEngine() == Engine.SYSTEM_TOOL_PROVIDER) {
            assertTrue(result.ok(), () -> String.join("\n", result.errors()));
            assertEquals("tool-provider", result.engine());
            assertNotNull(result.classes().get("sample.Hello"));
            assertTrue(result.classes().containsKey("sample.Hello"));
        } else {
            assertFalse(result.ok());
            assertTrue(result.errors().get(0).contains("No Java compiler available"),
                    "fallback must produce explicit operator guidance");
        }
    }

    @Test
    void compilesGraphExecutableImplementation() throws Exception {
        CompiledClasses result = service.compile("graph.generated.Graph_demo", """
                package graph.generated;

                import graph.runtime.*;
                import java.util.Map;
                import java.util.Set;

                public final class Graph_demo implements GraphExecutable {
                    public String graphId() { return "demo"; }
                    public Set<String> eventNodeIds() { return Set.of("ev"); }
                    public void execute(String eventNodeId, Map<String,Object> payload,
                            InvocationContext ctxArg, RuntimeServices svcArg) {
                        svcArg.log("ran");
                    }
                }
                """);

        if (result.ok()) {
            byte[] bytes = result.classes().get("graph.generated.Graph_demo");
            assertNotNull(bytes);
            assertEquals(0xCAFEBABEL, readMagic(bytes));
        } else {
            assertTrue(result.errors().get(0).contains("No Java compiler available"));
        }
    }

    @Test
    void syntaxErrorsReportedNotThrown() {
        CompiledClasses result = service.compile("sample.Broken", """
                package sample;
                public class Broken { this is not java }
                """);
        assertFalse(result.ok());
        assertFalse(result.errors().isEmpty());
    }

    @Test
    void referencesAgainstAbiResolvedFromCompileClasspath() {
        if (service.availableEngine() != Engine.SYSTEM_TOOL_PROVIDER) {
            return;
        }
        String source = """
                package sample;

                import graph.runtime.GraphExecutable;
                import graph.runtime.InvocationContext;
                import graph.runtime.RuntimeServices;

                public class UsesAbi implements GraphExecutable {
                    public String graphId() { return "x"; }
                    public java.util.Set<String> eventNodeIds() { return java.util.Set.of(); }
                    public void execute(String e, java.util.Map<String,Object> p,
                            InvocationContext c, RuntimeServices s) { }
                }
                """;
        CompiledClasses result = service.compile("sample.UsesAbi", source);
        assertTrue(result.ok(), () -> String.join("\n", result.errors()));
        assertTrue(result.classes().containsKey("sample.UsesAbi"));
    }

    private static long readMagic(byte[] bytes) {
        return ((long) (bytes[0] & 0xFF) << 24)
                | ((bytes[1] & 0xFF) << 16)
                | ((bytes[2] & 0xFF) << 8)
                | (bytes[3] & 0xFF);
    }
}
