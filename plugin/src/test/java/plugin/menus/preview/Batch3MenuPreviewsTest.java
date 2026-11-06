package plugin.menus.preview;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import arc.files.Fi;
import arc.func.Boolf;
import arc.func.Cons;
import arc.struct.StringMap;
import common.server.Server;
import mindustry.maps.Map;
import org.junit.jupiter.api.Test;
import plugin.gateway.ApiGateway;
import plugin.grief.GreifLoginMenu;
import plugin.grief.GriefMenu;
import plugin.hub.GlobalServerListMenu;
import plugin.hub.PaginationRequest;
import plugin.hub.ServerListMenu;
import plugin.hub.ServerRedirectMenu;
import plugin.maprating.MapRatingService;
import plugin.maprating.RateMapMenu;
import plugin.maprecord.MapRecord;
import plugin.maprecord.MapRecordMenuState;
import plugin.maprecord.MapRecordPlayer;
import plugin.maprecord.MapRecordsMenu;
import plugin.session.PlayerInfoMenu;
import plugin.session.Session;
import plugin.session.SessionService;
import plugin.trail.TrailMenu;
import plugin.trail.TrailService;
import plugin.trail.TrailService.Trail;

import static org.junit.jupiter.api.Assertions.assertTrue;

class Batch3MenuPreviewsTest {

    private static final Path OUTPUT_DIR = Path.of("build", "menu-previews", "batch3");

    private Map createMap(String filename, String name) {
        StringMap tags = new StringMap();
        tags.put("name", name);
        return new Map(new Fi(filename), 100, 100, tags, true, 0, 146);
    }

    @Test
    void renderBatch3MenusInAllViewports() {
        Server server1 = new Server()
                .setId(UUID.randomUUID())
                .setName("Survival EU Alpha")
                .setDescription("Official survival sector with active defenses and custom waves")
                .setMode("survival")
                .setMapName("Ancient Caldera")
                .setPlayers(12);

        Server server2 = new Server()
                .setId(UUID.randomUUID())
                .setName("Attack Arena Asia")
                .setDescription("High intensity attack match against enemy core bases")
                .setMode("attack")
                .setMapName("Fortress Assault")
                .setPlayers(8);

        List<Server> mockServers = List.of(server1, server2);

        ApiGateway mockGateway = new ApiGateway(null, null) {
            @Override
            public List<Server> getServers(PaginationRequest request) {
                return mockServers;
            }
        };

        GlobalServerListMenu globalServerListMenu = new GlobalServerListMenu(mockGateway);
        globalServerListMenu.setState(0);

        ServerListMenu serverListMenu = new ServerListMenu(mockGateway);
        serverListMenu.setState(0);

        ServerRedirectMenu serverRedirectMenu = new ServerRedirectMenu(mockGateway);
        serverRedirectMenu.setState(server1);

        Map sampleMap = createMap("craters.msav", "Ancient Craters");
        MapRatingService mapRatingService = new MapRatingService(null);
        RateMapMenu rateMapMenu = new RateMapMenu(mapRatingService);
        rateMapMenu.setState(sampleMap);

        List<MapRecordPlayer> players = List.of(
                new MapRecordPlayer("u1", "PlayerOne"),
                new MapRecordPlayer("u2", "SpeedRunner_99"),
                new MapRecordPlayer("u3", "DefenderHero")
        );
        MapRecord rec1 = new MapRecord(1L, "Ancient Craters", "survival", 1254000L, players, System.currentTimeMillis());
        MapRecord rec2 = new MapRecord(2L, "Ancient Craters", "survival", 1580000L, List.of(new MapRecordPlayer("u4", "SoloMaster")), System.currentTimeMillis());
        MapRecordMenuState recordState = new MapRecordMenuState("Ancient Craters", "survival", List.of(rec1, rec2));

        MapRecordsMenu mapRecordsMenu = new MapRecordsMenu();
        mapRecordsMenu.setState(recordState);

        TrailService trailService = new TrailService(null, null);
        Trail.create(trailService, "Fire Trail", 200, null);
        Trail.create(trailService, "Lightning Spark", 500, null);
        Trail.create(trailService, "Cosmic Glow", 1000, null);
        TrailMenu trailMenu = new TrailMenu(trailService, null);
        trailMenu.setState(0);

        SessionService mockSessionService = new SessionService(null, null, null) {
            @Override
            public void each(Cons<Session> item) {
                item.get(MockSession.builder().name("PlayerOne").build());
                item.get(MockSession.builder().name("SpeedRunner_99").build());
                item.get(MockSession.builder().name("MegaBuilder").build());
            }

            @Override
            public void each(Boolf<Session> pred, Cons<Session> item) {
                Session s1 = MockSession.builder().name("SuspiciousUser").build();
                Session s2 = MockSession.builder().name("TrollBuilder").build();
                if (pred.get(s1)) {
                    item.get(s1);
                }
                if (pred.get(s2)) {
                    item.get(s2);
                }
            }
        };
        GriefMenu griefMenu = new GriefMenu(mockSessionService, null);
        PlayerInfoMenu playerInfoMenu = new PlayerInfoMenu(mockSessionService);

        GreifLoginMenu greifLoginMenu = new GreifLoginMenu();
        greifLoginMenu.setState("https://verify.mindustry-tool.com/auth");

        MenuViewport[] viewports = {
                MenuViewport.DESKTOP,
                MenuViewport.MOBILE_LANDSCAPE,
                MenuViewport.MOBILE_PORTRAIT
        };

        for (MenuViewport vp : viewports) {
            String suffix = "_" + vp.name().toLowerCase() + ".png";
            Session session = MockSession.builder().locale("en").mobile(vp.mobile).name("Moderator").build();

            MenuSnapshot.builder().menu(globalServerListMenu).session(session).viewport(vp).capture(OUTPUT_DIR.resolve("global_server_list" + suffix));
            MenuSnapshot.builder().menu(serverListMenu).session(session).viewport(vp).capture(OUTPUT_DIR.resolve("server_list" + suffix));
            MenuSnapshot.builder().menu(serverRedirectMenu).session(session).viewport(vp).capture(OUTPUT_DIR.resolve("server_redirect" + suffix));
            MenuSnapshot.builder().menu(rateMapMenu).session(session).viewport(vp).capture(OUTPUT_DIR.resolve("rate_map" + suffix));
            MenuSnapshot.builder().menu(mapRecordsMenu).session(session).viewport(vp).capture(OUTPUT_DIR.resolve("map_records" + suffix));
            MenuSnapshot.builder().menu(trailMenu).session(session).viewport(vp).capture(OUTPUT_DIR.resolve("trail" + suffix));
            MenuSnapshot.builder().menu(griefMenu).session(session).viewport(vp).capture(OUTPUT_DIR.resolve("grief" + suffix));
            MenuSnapshot.builder().menu(playerInfoMenu).session(session).viewport(vp).capture(OUTPUT_DIR.resolve("player_info" + suffix));
            MenuSnapshot.builder().menu(greifLoginMenu).session(session).viewport(vp).capture(OUTPUT_DIR.resolve("grief_login" + suffix));
        }

        assertTrue(Files.exists(OUTPUT_DIR.resolve("global_server_list_desktop.png")));
        assertTrue(Files.exists(OUTPUT_DIR.resolve("global_server_list_mobile_landscape.png")));
        assertTrue(Files.exists(OUTPUT_DIR.resolve("global_server_list_mobile_portrait.png")));
        assertTrue(Files.exists(OUTPUT_DIR.resolve("rate_map_mobile_landscape.png")));
        assertTrue(Files.exists(OUTPUT_DIR.resolve("map_records_mobile_portrait.png")));
        assertTrue(Files.exists(OUTPUT_DIR.resolve("trail_mobile_landscape.png")));
        assertTrue(Files.exists(OUTPUT_DIR.resolve("grief_mobile_landscape.png")));
        assertTrue(Files.exists(OUTPUT_DIR.resolve("player_info_mobile_portrait.png")));
        assertTrue(Files.exists(OUTPUT_DIR.resolve("grief_login_mobile_portrait.png")));
    }
}
