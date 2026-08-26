package plugin.graph.services;

import graph.registry.EventDescriptor;
import graph.registry.FunctionDescriptor;
import graph.registry.GraphRegistry;
import graph.registry.Invoker;
import graph.registry.Overload;
import graph.registry.ParamDescriptor;
import graph.registry.TypeDescriptor;
import graph.types.TypeRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphDiscoveryHandlersTest {

    private GraphRegistry registry;
    private GraphDiscoveryHandlers handlers;

    @BeforeEach
    void setUp() {
        registry = new GraphRegistry();
        registry.register(FunctionDescriptor.builder("java.lang.String.valueOf")
                .displayName("Value Of")
                .category("strings")
                .ownerType("server")
                .description("Converts a value to text")
                .overload(Overload.of(TypeRef.STRING,
                        new ParamDescriptor("v", TypeRef.of("Object"))))
                .build(), (hash, args, ctx) -> String.valueOf(args[0]));
        registry.register(FunctionDescriptor.builder("mindustry.player.kick")
                .displayName("Kick")
                .category("players")
                .ownerType("player")
                .description("Kicks a player")
                .overload(Overload.of(TypeRef.BOOLEAN,
                        new ParamDescriptor("p", TypeRef.of("Player"))))
                .build(), (hash, args, ctx) -> true);
        registry.register(new EventDescriptor("mindustry.event.player.join",
                "Join", List.of(new ParamDescriptor("player", TypeRef.of("Player"))),
                "players", "Fires when a player joins"));
        registry.register(new TypeDescriptor("String", "primitive", "Text value"));

        handlers = new GraphDiscoveryHandlers(registry);
    }

    @Test
    void searchReturnsMatchesPaginationAndFingerprint() {
        GraphDiscoveryHandlers.SearchRequest req =
                new GraphDiscoveryHandlers.SearchRequest();
        req.query = "kick";
        Map<String, Object> out = handlers.search(req);

        assertNotNull(out.get("fingerprint"));
        assertTrue(out.get("fingerprint").toString().length() > 0);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) out.get("results");
        assertEquals(1, results.size(),
                "registry fns=" + registry.functions().size()
                        + " ev=" + registry.eventsList().size()
                        + " out=" + out);
        assertEquals("mindustry.player.kick", results.get(0).get("id"));
    }

    @Test
    void searchFiltersByCategoryAndOwnerTypeWithLimit() {
        GraphDiscoveryHandlers.SearchRequest catReq =
                new GraphDiscoveryHandlers.SearchRequest();
        catReq.category = "players";
        catReq.kind = "function";
        catReq.limit = 10;
        assertEquals(1,
                ((List<?>) handlers.search(catReq).get("results")).size());

        GraphDiscoveryHandlers.SearchRequest ownerReq =
                new GraphDiscoveryHandlers.SearchRequest();
        ownerReq.ownerType = "server";
        ownerReq.kind = "function";
        ownerReq.limit = 5;
        assertEquals(1,
                ((List<?>) handlers.search(ownerReq).get("results")).size());
        assertEquals("java.lang.String.valueOf",
                ((List<?>) handlers.search(ownerReq).get("results")).get(0)
                        .toString().contains("valueOf") ? "java.lang.String.valueOf" : "");
    }

    @Test
    void detailResolvesFunctionEventAndTypeById() {
        GraphDiscoveryHandlers.DetailRequest fnReq =
                new GraphDiscoveryHandlers.DetailRequest();
        fnReq.id = "mindustry.event.player.join";
        Map<String, Object> evOut = handlers.detail(fnReq);
        assertEquals(Boolean.TRUE, evOut.get("found"));
        assertEquals("event", evOut.get("kind"));
        assertTrue(evOut.get("descriptor").toString().contains("Join"));

        GraphDiscoveryHandlers.DetailRequest typeReq =
                new GraphDiscoveryHandlers.DetailRequest();
        typeReq.id = "String";
        Map<String, Object> tyOut = handlers.detail(typeReq);
        assertEquals("type", tyOut.get("kind"));

        GraphDiscoveryHandlers.DetailRequest missReq =
                new GraphDiscoveryHandlers.DetailRequest();
        missReq.id = "no.such.thing";
        assertFalse((boolean) handlers.detail(missReq).get("found"));
        assertFalse((boolean) handlers.detail(null).get("found"));
    }

    @Test
    void eventsAndTypesListingsReturnRegisteredEntries() {
        List<Object> events = handlers.events();
        assertEquals(1, events.size());
        assertTrue(events.get(0).toString().contains("player.join"));

        List<Object> types = handlers.types();
        assertFalse(types.isEmpty());
        assertTrue(types.stream().anyMatch(t -> t.toString().contains("String")));
    }
}
