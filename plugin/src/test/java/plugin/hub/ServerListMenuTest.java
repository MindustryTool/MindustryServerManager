package plugin.hub;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import common.server.Server;
import mindustry.gen.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import plugin.gateway.ApiGateway;
import plugin.menus.MenuContext;
import plugin.menus.preview.MockSession;
import plugin.session.Session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerListMenuTest {

    private Session session;

    @BeforeEach
    void setUp() {
        session = MockSession.builder().locale("en").build();
    }

    private Server createServer(String name, int players, String mapName, String mode) {
        Server server = new Server();
        server.setId(UUID.randomUUID());
        server.setName(name);
        server.setPlayers(players);
        server.setMapName(mapName);
        server.setMode(mode);
        server.setDescription("Description for " + name);
        return server;
    }

    @Test
    void testFillScreenIsEnabled() {
        ServerListMenu menu = new ServerListMenu(null, new ServerImageService());
        assertTrue(menu.isFillScreen(), "ServerListMenu should be fullscreen");
    }

    @Test
    void testBuildEmptyServerList() {
        ApiGateway mockGateway = new ApiGateway(null, null, null) {
            @Override
            public List<Server> getServers(PaginationRequest request) {
                return List.of();
            }
        };

        ServerListMenu menu = new ServerListMenu(mockGateway, new ServerImageService());
        menu.setSession(session);
        menu.setState(0);

        MenuContext<Integer> ctx = new MenuContext<>(session, 0, 1L);
        menu.build(ctx);

        assertEquals("List of all servers", menu.getTitle());
        assertTrue(ctx.getActions().isEmpty(), "No actions should be registered for empty servers");
    }

    @Test
    void testBuildWithServersRegistersActionsAndStreamsTextures() {
        Server onlineServer = createServer("Survival #1", 12, "Ancient Caldera", "survival");
        Server offlineServer = createServer("Sandbox Test", 0, null, "sandbox");
        List<Server> mockServers = List.of(onlineServer, offlineServer);

        ApiGateway mockGateway = new ApiGateway(null, null, null) {
            @Override
            public List<Server> getServers(PaginationRequest request) {
                return mockServers;
            }
        };

        AtomicBoolean streamCalled = new AtomicBoolean(false);
        ServerImageService stubImageService = new ServerImageService() {
            @Override
            public void streamServerTextures(Player player, Iterable<Server> servers) {
                streamCalled.set(true);
            }
        };

        ServerListMenu menu = new ServerListMenu(mockGateway, stubImageService);
        menu.setSession(session);
        menu.setState(0);

        MenuContext<Integer> ctx = new MenuContext<>(session, 0, 1L);
        menu.build(ctx);

        assertEquals("List of all servers", menu.getTitle());
        assertTrue(streamCalled.get(), "Texture streaming should be invoked for listed servers");
        // Each server should register 1 connect action
        assertEquals(2, ctx.getActions().size(), "Each server card should register a connect action");
    }

    @Test
    void testPaginationControlsAppearWhenPageIsFull() {
        List<Server> fullPage = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            fullPage.add(createServer("Server " + i, i, "Map " + i, "survival"));
        }

        ApiGateway mockGateway = new ApiGateway(null, null, null) {
            @Override
            public List<Server> getServers(PaginationRequest request) {
                return fullPage;
            }
        };

        ServerListMenu menu = new ServerListMenu(mockGateway, new ServerImageService());
        menu.setSession(session);
        menu.setState(0);

        MenuContext<Integer> ctx = new MenuContext<>(session, 0, 1L);
        menu.build(ctx);

        // 12 server connect actions + 1 "next" pagination action
        assertEquals(13, ctx.getActions().size());
    }

    @Test
    void testPreviousButtonAppearsOnPageGreaterZero() {
        List<Server> pageServers = List.of(createServer("S1", 1, "Map 1", "survival"));

        ApiGateway mockGateway = new ApiGateway(null, null, null) {
            @Override
            public List<Server> getServers(PaginationRequest request) {
                return pageServers;
            }
        };

        ServerListMenu menu = new ServerListMenu(mockGateway, new ServerImageService());
        menu.setSession(session);
        menu.setState(1);

        MenuContext<Integer> ctx = new MenuContext<>(session, 1, 1L);
        menu.build(ctx);

        // 1 server connect action + 1 "previous" pagination action (since size < 12, no
        // "next")
        assertEquals(2, ctx.getActions().size());
    }
}
