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

class GlobalServerListMenuTest {

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
        server.setDescription("Admin redirect to " + name);
        return server;
    }

    @Test
    void testFillScreenIsEnabled() {
        GlobalServerListMenu menu = new GlobalServerListMenu(null, new ServerImageService());
        assertTrue(menu.isFillScreen(), "GlobalServerListMenu should be fullscreen");
    }

    @Test
    void testBuildEmptyServerList() {
        ApiGateway mockGateway = new ApiGateway(null, null, null) {
            @Override
            public List<Server> getServers(PaginationRequest request) {
                return List.of();
            }
        };

        GlobalServerListMenu menu = new GlobalServerListMenu(mockGateway, new ServerImageService());
        menu.setSession(session);
        menu.setState(0);

        MenuContext<Integer> ctx = new MenuContext<>(session, 0, 1L);
        menu.build(ctx);

        assertEquals("Servers", menu.getTitle());
        assertTrue(ctx.getActions().isEmpty());
    }

    @Test
    void testBuildWithServersRegistersRedirectAllActionsAndStreamsTextures() {
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

        GlobalServerListMenu menu = new GlobalServerListMenu(mockGateway, stubImageService);
        menu.setSession(session);
        menu.setState(0);

        MenuContext<Integer> ctx = new MenuContext<>(session, 0, 1L);
        menu.build(ctx);

        assertEquals("Servers", menu.getTitle());
        assertTrue(streamCalled.get(), "Texture streaming should be called for listed servers");
        assertEquals(2, ctx.getActions().size(), "Each server card should register a redirectAll action");
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

        GlobalServerListMenu menu = new GlobalServerListMenu(mockGateway, new ServerImageService());
        menu.setSession(session);
        menu.setState(0);

        MenuContext<Integer> ctx = new MenuContext<>(session, 0, 1L);
        menu.build(ctx);

        assertEquals(13, ctx.getActions().size(), "12 servers + 1 next action");
    }
}
