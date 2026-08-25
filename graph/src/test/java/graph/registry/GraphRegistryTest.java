package graph.registry;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import graph.runtime.InvocationContext;
import graph.types.TypeRef;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphRegistryTest {

    private static FunctionDescriptor fn(String id, String category, String owner, Overload... overloads) {
        FunctionDescriptor.Builder builder = FunctionDescriptor.builder(id)
                .category(category)
                .ownerType(owner);
        for (Overload overload : overloads) {
            builder.overload(overload);
        }
        return builder.build();
    }

    @Test
    void programmaticRegistrationAndLookup() throws Exception {
        GraphRegistry registry = new GraphRegistry();
        Invoker invoker = (hash, args, ctx) -> "ok";
        registry.register(fn("myplugin.mute.player", "Moderation", "Player", Overload.of(TypeRef.BOOLEAN)), invoker);

        assertTrue(registry.hasFunction("myplugin.mute.player"));
        assertEquals("Moderation", registry.function("myplugin.mute.player").category());
        assertSame(invoker, registry.invoker("myplugin.mute.player"));
    }

    @Test
    void duplicateProgrammaticIdRejectedAndOriginalKept() throws Exception {
        GraphRegistry registry = new GraphRegistry();
        Invoker first = (hash, args, ctx) -> 1;
        registry.register(fn("a.b", "", "", Overload.of(TypeRef.INT)), first);
        assertThrows(IllegalArgumentException.class,
                () -> registry.register(fn("a.b", "", "", Overload.of(TypeRef.INT)), (hash, args, ctx) -> 2));
        assertEquals(1, registry.invoker("a.b").invoke("", new Object[0], InvocationContext.root()));
    }

    @Test
    void lazyPageMaterializationHappensOnce() {
        GraphRegistry registry = new GraphRegistry();
        AtomicInteger pageLoads = new AtomicInteger();

        RegistryIndexEntry entry = RegistryIndexEntry.function(
                "mindustry.player.sendMessage", "Communication", "Player", "(Player,String)");
        registry.loadIndex(List.of(entry), id -> {
            pageLoads.incrementAndGet();
            return FunctionDescriptor.builder(id)
                    .overload(Overload.of(TypeRef.BOOLEAN,
                            new ParamDescriptor("player", TypeRef.of("Player")),
                            new ParamDescriptor("message", TypeRef.STRING)))
                    .build();
        });

        assertEquals(0, pageLoads.get());

        FunctionDescriptor descriptor = registry.function("mindustry.player.sendMessage");
        assertEquals("mindustry.player.sendMessage", descriptor.id());
        assertEquals(1, pageLoads.get());

        registry.function("mindustry.player.sendMessage");
        registry.invoker("mindustry.player.sendMessage");
        assertEquals(1, pageLoads.get(), "page must materialize exactly once");
    }

    @Test
    void unknownIdFailsWithoutPageSource() {
        GraphRegistry registry = new GraphRegistry();
        assertNull(registry.property("nope"));
        assertThrows(IllegalArgumentException.class, () -> registry.function("nope"));
    }

    @Test
    void searchFiltersByKindCategoryOwnerAndText() {
        GraphRegistry registry = new GraphRegistry();
        registry.loadIndex(List.of(
                RegistryIndexEntry.function("mindustry.player.sendMessage", "Communication", "Player",
                        "Sends a message"),
                RegistryIndexEntry.function("mindustry.player.kick", "Administration", "Player", ""),
                RegistryIndexEntry.function("mindustry.world.tile", "World", "World", ""),
                RegistryIndexEntry.event("mindustry.player.join", "", "", "")), null);

        assertEquals(4, registry.search(GraphRegistry.SearchQuery.all()).size());

        List<RegistryIndexEntry> byKind = registry.search(
                new GraphRegistry.SearchQuery("", "event", "", "", 0, 10));
        assertEquals(1, byKind.size());
        assertEquals("mindustry.player.join", byKind.get(0).id());

        List<RegistryIndexEntry> byCategory = registry.search(
                new GraphRegistry.SearchQuery("", "", "Communication", "", 0, 10));
        assertEquals(1, byCategory.size());

        List<RegistryIndexEntry> byOwner = registry.search(
                new GraphRegistry.SearchQuery("", "", "", "Player", 0, 10));
        assertEquals(2, byOwner.size());

        List<RegistryIndexEntry> byText = registry.search(GraphRegistry.SearchQuery.text("kick"));
        assertEquals(1, byText.size());
        assertEquals("mindustry.player.kick", byText.get(0).id());

        List<RegistryIndexEntry> byToken = registry.search(GraphRegistry.SearchQuery.text("send"));
        assertEquals(1, byToken.size());
        assertEquals("mindustry.player.sendMessage", byToken.get(0).id());
    }

    @Test
    void searchRanksExactMatchesFirst() {
        GraphRegistry registry = new GraphRegistry();
        registry.loadIndex(List.of(
                RegistryIndexEntry.function("player.send.message.extra", "", "", ""),
                RegistryIndexEntry.function("player.send", "", "", "")), null);

        List<RegistryIndexEntry> results = registry.search(GraphRegistry.SearchQuery.text("send"));
        assertEquals("player.send", results.get(0).id());
    }

    @Test
    void searchPagination() {
        GraphRegistry registry = new GraphRegistry();
        registry.loadIndex(List.of(
                RegistryIndexEntry.function("f.a", "", "", ""),
                RegistryIndexEntry.function("f.b", "", "", ""),
                RegistryIndexEntry.function("f.c", "", "", "")), null);

        List<RegistryIndexEntry> page = registry.search(
                new GraphRegistry.SearchQuery("", "", "", "", 1, 2));
        assertEquals(2, page.size());
        assertEquals("f.b", page.get(0).id());
        assertEquals("f.c", page.get(1).id());
    }

    @Test
    void aliasSearchMatchesOnlyWhenFunctionKnown() {
        GraphRegistry registry = new GraphRegistry();
        registry.loadIndex(List.of(
                RegistryIndexEntry.function("my.say", "", "", "")), id ->
                FunctionDescriptor.builder(id).alias("talk")
                        .overload(Overload.of(TypeRef.BOOLEAN)).build());

        assertEquals(0, registry.search(GraphRegistry.SearchQuery.text("talk")).size(),
                "alias of unloaded page must not match");

        registry.function("my.say");

        assertEquals(1, registry.search(GraphRegistry.SearchQuery.text("talk")).size(),
                "alias matches after materialization");
    }

    @Test
    void fingerprintIsOrderIndependentAndChangeSensitive() {
        GraphRegistry registry = new GraphRegistry();
        Invoker noop = (hash, args, ctx) -> null;
        registry.register(fn("a.one", "", "",
                OverloadHashHelper.overload()), noop);
        registry.register(fn("a.two", "", "",
                OverloadHashHelper.overload2()), noop);

        String fp12 = registry.fingerprint(Set.of("a.one", "a.two"));
        String fp21 = registry.fingerprint(Set.of("a.two", "a.one"));
        assertEquals(fp12, fp21);

        String fpOne = registry.fingerprint(Set.of("a.one"));
        assertNotEquals(fp12, fpOne);

        GraphRegistry other = new GraphRegistry();
        other.register(fn("a.one", "", "",
                FunctionDescriptorsWithDifferentReturn.overload()), noop);
        other.register(fn("a.two", "", "", OverloadHashHelper.overload2()), noop);
        assertNotEquals(fp12, other.fingerprint(Set.of("a.one", "a.two")),
                "signature change must change fingerprint");
    }

    @Test
    void indexRejectsDuplicateEntries() {
        GraphRegistry registry = new GraphRegistry();
        assertThrows(IllegalArgumentException.class, () -> registry.loadIndex(List.of(
                RegistryIndexEntry.function("dup.id", "", "", ""),
                RegistryIndexEntry.function("dup.id", "", "", "")), null));
    }

    @Test
    void invalidationScopeOnlyTouchesConsumersOfChangedFunction() {
        GraphRegistry registry = new GraphRegistry();
        Invoker noop = (hash, args, ctx) -> null;
        Overload f1v1 = Overload.of(TypeRef.INT, new ParamDescriptor("x", TypeRef.INT));
        Overload f1v2 = Overload.of(TypeRef.STRING, new ParamDescriptor("x", TypeRef.INT));
        Overload f2 = Overload.of(TypeRef.BOOLEAN);

        registry.register(fn("mod.f1", "", "", f1v1), noop);
        registry.register(fn("mod.f2", "", "", f2), noop);

        Set<String> graphGCallsF2Only = Set.of("mod.f2");
        Set<String> graphHCallsF1 = Set.of("mod.f1");

        String gBefore = registry.fingerprint(graphGCallsF2Only);
        String hBefore = registry.fingerprint(graphHCallsF1);

        registry.replace(fn("mod.f1", "", "", f1v2), noop);

        assertEquals(gBefore, registry.fingerprint(graphGCallsF2Only),
                "unrelated graph must keep its fingerprint");
        assertNotEquals(hBefore, registry.fingerprint(graphHCallsF1),
                "affected graph must get new fingerprint");
    }

    private static final class OverloadHashHelper {
        static Overload overload() {
            return Overload.of(TypeRef.INT, new ParamDescriptor("x", TypeRef.INT));
        }

        static Overload overload2() {
            return Overload.of(TypeRef.STRING, new ParamDescriptor("y", TypeRef.STRING));
        }
    }

    private static final class FunctionDescriptorsWithDifferentReturn {
        static Overload overload() {
            return Overload.of(TypeRef.BOOLEAN, new ParamDescriptor("x", TypeRef.INT));
        }
    }
}



