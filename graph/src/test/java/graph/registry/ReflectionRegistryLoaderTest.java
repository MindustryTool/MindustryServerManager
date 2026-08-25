package graph.registry;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import graph.runtime.InvocationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReflectionRegistryLoaderTest {

    @Test
    void loadsPublicMethodsAsDescriptorsWithOverloads() {
        List<ReflectionRegistryLoader.LoadedEntry> entries =
                ReflectionRegistryLoader.loadClass(StringBuilder.class, null);

        ReflectionRegistryLoader.LoadedEntry reverse = entries.stream()
                .filter(e -> e.id().endsWith(".reverse"))
                .findFirst().orElseThrow();
        assertEquals("java.lang.StringBuilder.reverse", reverse.id());
        assertEquals("StringBuilder", reverse.descriptor().ownerType());
        assertEquals(1, reverse.descriptor().overloads().size());
        assertEquals("StringBuilder",
                reverse.descriptor().overloads().get(0).returnType().base());

        ReflectionRegistryLoader.LoadedEntry append = entries.stream()
                .filter(e -> e.id().endsWith(".append"))
                .findFirst().orElseThrow();
        assertTrue(append.descriptor().overloads().size() > 5,
                "append must surface many overloads");
        Map<String, Invoker> byHash = append.invokersByHash();
        assertTrue(byHash.size() >= 5,
                "one cached invoker per distinct graph-level signature "
                        + "(coarse typing may merge erased-duplicate overloads)");
    }

    @Test
    void skipsObjectMethodsAndNonPublic() {
        List<ReflectionRegistryLoader.LoadedEntry> entries =
                ReflectionRegistryLoader.loadClass(Math.class, "math");

        assertTrue(entries.stream().noneMatch(e -> e.id().endsWith(".wait")));
        assertTrue(entries.stream().noneMatch(e -> e.id().endsWith(".hashCode")));

        ReflectionRegistryLoader.LoadedEntry abs = entries.stream()
                .filter(e -> e.id().endsWith(".abs")).findFirst().orElseThrow();
        assertEquals("math", abs.descriptor().category());
        assertTrue(abs.descriptor().overloads().size() >= 4,
                "int/long/float/double abs overloads");
    }

    @Test
    void cachedInvokerDispatchesStaticAndInstanceMembers() throws Exception {
        List<ReflectionRegistryLoader.LoadedEntry> mathEntries =
                ReflectionRegistryLoader.loadClass(Math.class, null);
        ReflectionRegistryLoader.LoadedEntry abs = mathEntries.stream()
                .filter(e -> e.id().endsWith(".abs")).findFirst().orElseThrow();

        Overload intAbs = abs.descriptor().overloads().stream()
                .filter(o -> o.signature().equals("(Int):Int"))
                .findFirst().orElseThrow();
        Object result = abs.invokersByHash().get(intAbs.hash())
                .invoke(intAbs.hash(), new Object[]{-7}, InvocationContext.root());
        assertEquals(7, ((Integer) result).intValue());
        assertNull(null);

        List<ReflectionRegistryLoader.LoadedEntry> sbEntries =
                ReflectionRegistryLoader.loadClass(StringBuilder.class, null);
        ReflectionRegistryLoader.LoadedEntry reverse = sbEntries.stream()
                .filter(e -> e.id().endsWith(".reverse")).findFirst().orElseThrow();
        StringBuilder receiver = new StringBuilder("abc");
        Object reversed = reverse.invokersByHash().values().iterator().next()
                .invoke(reverse.descriptor().overloads().get(0).hash(),
                        new Object[]{receiver}, InvocationContext.root());
        assertEquals("cba", receiver.toString(), "instance method mutated receiver");
        assertEquals(receiver, reversed, "StringBuilder.reverse returns this");
    }

    @Test
    void overloadHashesDistinguishSignatures() throws Exception {
        List<ReflectionRegistryLoader.LoadedEntry> entries =
                ReflectionRegistryLoader.loadClass(Math.class, null);
        ReflectionRegistryLoader.LoadedEntry abs = entries.stream()
                .filter(e -> e.id().endsWith(".abs")).findFirst().orElseThrow();

        String intHash = hashOf(abs, "(Int):Int");
        String doubleHash = hashOf(abs, "(Double):Double");
        assertNotEquals(intHash, doubleHash);

        InvocationContext ctx = InvocationContext.root();
        assertInstanceOf(Double.class,
                abs.invokersByHash().get(doubleHash)
                        .invoke(doubleHash, new Object[]{-2.5d}, ctx));
        assertInstanceOf(Integer.class,
                abs.invokersByHash().get(intHash)
                        .invoke(intHash, new Object[]{-3}, ctx));
    }

    @Test
    void registryRoundTripWithReflectiveEntries() throws Exception {
        GraphRegistry registry = new GraphRegistry();
        for (ReflectionRegistryLoader.LoadedEntry entry
                : ReflectionRegistryLoader.loadClass(StringBuilder.class, null)) {
            registry.register(entry.descriptor(),
                    new ReflectionRegistryLoader.OverloadDispatchInvoker(entry.invokersByHash()));
        }

        assertTrue(registry.hasFunction("java.lang.StringBuilder.reverse"));
        FunctionDescriptor descriptor = registry.function("java.lang.StringBuilder.reverse");
        assertNotNull(descriptor);

        String hash = descriptor.overloads().get(0).hash();
        StringBuilder receiver = new StringBuilder("xyz");
        registry.invoker("java.lang.StringBuilder.reverse")
                .invoke(hash, new Object[]{receiver}, InvocationContext.root());
        assertEquals("zyx", receiver.toString());
    }

    private static String hashOf(ReflectionRegistryLoader.LoadedEntry entry,
                                 String signature) {
        for (Overload overload : entry.descriptor().overloads()) {
            if (overload.signature().equals(signature)) {
                return overload.hash();
            }
        }
        throw new AssertionError("no overload with signature " + signature);
    }

    @Test
    void primitiveNameMapping() {
        assertEquals("String", ReflectionRegistryLoader.simpleName(String.class));
        assertEquals("Int", ReflectionRegistryLoader.simpleName(int.class));
        assertEquals("Boolean", ReflectionRegistryLoader.simpleName(boolean.class));
        assertEquals("FakePlayer", ReflectionRegistryLoader.simpleName(FakePlayer.class));
        assertNotNull(ReflectionRegistryLoader.returnType(void.class));
        assertEquals("Void", ReflectionRegistryLoader.returnType(void.class).base());
    }

    static final class FakePlayer {
        public String name() {
            return "fake";
        }
    }
}
