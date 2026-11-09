package plugin.menus.preview;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import arc.graphics.Color;
import arc.graphics.Pixmap;
import arc.graphics.Texture;
import arc.graphics.g2d.TextureRegion;
import arc.files.Fi;
import arc.func.Boolf;
import arc.func.Cons;
import arc.struct.Seq;
import arc.struct.StringMap;
import common.server.Server;
import common.util.Pair;
import mindustry.content.Fx;
import mindustry.game.Team;
import mindustry.gen.Player;
import mindustry.maps.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import plugin.dailytask.DailyTask;
import plugin.dailytask.DailyTaskService;
import plugin.dailytask.TaskMenu;
import plugin.gamemode.catali.data.CataliCommonUpgrade;
import plugin.gamemode.catali.data.CataliTeamData;
import plugin.gamemode.catali.menu.AIPickMenu;
import plugin.gamemode.catali.menu.AbandonMenu;
import plugin.gamemode.catali.menu.AssignNextLeaderMenu;
import plugin.gamemode.catali.menu.CommonUpgradeMenu;
import plugin.gamemode.catali.menu.CommonUpgradeQuantityMenu;
import plugin.gamemode.catali.menu.JoinTeamMenu;
import plugin.gamemode.catali.menu.RareUpgradeBuffSelectBuffMenu;
import plugin.gamemode.catali.menu.RareUpgradeBuffSelectUnitMenu;
import plugin.gamemode.catali.menu.RareUpgradeMenu;
import plugin.gamemode.catali.menu.RareUpgradeTierSelectUnitMenu;
import plugin.gamemode.sandbox.SandboxTeamMenu;
import plugin.gateway.ApiGateway;
import plugin.grief.GreifLoginMenu;
import plugin.grief.GriefMenu;
import plugin.hub.GlobalServerListMenu;
import plugin.hub.PaginationRequest;
import plugin.hub.ServerImageService;
import plugin.hub.ServerListMenu;
import plugin.hub.ServerRedirectMenu;
import plugin.maprating.MapRatingRepository;
import plugin.maprating.MapRatingService;
import plugin.maprating.MapRatingStat;
import plugin.maprating.RateMapMenu;
import plugin.maprecord.MapRecord;
import plugin.maprecord.MapRecordMenuState;
import plugin.maprecord.MapRecordPlayer;
import plugin.maprecord.MapRecordsMenu;
import plugin.menus.ServerMenu;
import plugin.session.LoginMenu;
import plugin.session.LoginService;
import plugin.session.PlayerInfoMenu;
import plugin.session.Session;
import plugin.session.SessionService;
import plugin.shop.GachaMenu;
import plugin.shop.GachaService;
import plugin.shop.ShopItem;
import plugin.shop.ShopMenu;
import plugin.shop.ShopRegistry;
import plugin.trail.TrailMenu;
import plugin.trail.TrailService;
import plugin.trail.TrailService.Trail;
import plugin.vote.RtvMenu;
import plugin.vote.RtvService;
import plugin.vote.VoteKickService;
import plugin.vote.VoteKickService.VoteKickSession;
import plugin.vote.VotePromptMenu;
import plugin.welcome.WelcomeMenu;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("renderer")
class RenderAllMenuPreviewsTest {

    private static final Path BASE_DIR = Path.of("build", "menu-previews");

    private static Path categoryDir(String folder) {
        Path dir = BASE_DIR.resolve(folder);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create preview dir: " + dir, e);
        }
        return dir;
    }

    private static Map createMap(String filename, String name) {
        StringMap tags = new StringMap();
        tags.put("name", name);
        return new Map(new Fi(filename), 100, 100, tags, true, 0, 146);
    }

    private static void mockPreviewTexture(String name, Color topColor, Color bottomColor) {
        MenuPreviewBackend.getInstance().runOnGlThread(() -> {
            if (!arc.Core.atlas.has(name)) {
                int w = 240, h = 135;
                Pixmap pix = new Pixmap(w, h);
                for (int x = 0; x < w; x++) {
                    for (int y = 0; y < h; y++) {
                        float lerp = (float) y / h;
                        Color c = topColor.cpy().lerp(bottomColor, lerp);
                        if (x % 24 == 0 || y % 24 == 0) {
                            c.mul(1.22f);
                        }
                        pix.setRaw(x, y, c.rgba());
                    }
                }
                Texture tex = new Texture(pix);
                arc.Core.atlas.addRegion(name, new TextureRegion(tex));
            }
            return null;
        });
    }

    private static void render(ServerMenu<?> menu, Session session, String folder, String name) {
        Path target = categoryDir(folder).resolve(name + ".png");
        for (MenuViewport vp : MenuViewport.values()) {
            Session s = MockSession.builder()
                    .locale("en")
                    .mobile(vp.mobile)
                    .admin(session != null && session.player != null && session.player.admin)
                    .name(session != null && session.player != null ? session.player.name : "Player")
                    .build();
            MenuSnapshot.builder().menu(menu).session(s).viewport(vp).capture(target);
        }
    }

    private static void assertPreviewsExist(String folder, String name) {
        Path dir = BASE_DIR.resolve(folder);
        assertTrue(Files.exists(dir.resolve(name + "-desktop.png")));
        assertTrue(Files.exists(dir.resolve(name + "-mobile-landscape.png")));
        assertTrue(Files.exists(dir.resolve(name + "-mobile-portrait.png")));
    }

    @Test
    void renderCataliGamemode() {
        CataliTeamData team = new CataliTeamData(Team.sharded, "uuid_dev_test");
        team.level.commonUpgradePoints = 5;
        team.level.rareUpgradePoints = 3;
        team.useCommandAi = false;

        AbandonMenu abandonMenu = new AbandonMenu(null);
        abandonMenu.setState(team);

        AIPickMenu aiPickMenu = new AIPickMenu();
        aiPickMenu.setState(team);

        CommonUpgradeQuantityMenu quantityMenu = new CommonUpgradeQuantityMenu();
        quantityMenu.setState(Pair.of(team, CataliCommonUpgrade.DAMAGE));

        CommonUpgradeMenu commonUpgradeMenu = new CommonUpgradeMenu(quantityMenu);
        commonUpgradeMenu.setState(team);

        RareUpgradeMenu rareUpgradeMenu = new RareUpgradeMenu(
                new RareUpgradeTierSelectUnitMenu(null, null),
                new RareUpgradeBuffSelectUnitMenu((RareUpgradeBuffSelectBuffMenu) null));
        rareUpgradeMenu.setState(team);

        JoinTeamMenu joinTeamMenu = new JoinTeamMenu();

        AssignNextLeaderMenu assignLeaderMenu = new AssignNextLeaderMenu();
        assignLeaderMenu.setState(team);

        Session session = MockSession.builder().locale("en").build();
        render(abandonMenu, session, "catali", "abandon");
        render(aiPickMenu, session, "catali", "aipick");
        render(commonUpgradeMenu, session, "catali", "common_upgrade");
        render(quantityMenu, session, "catali", "upgrade_quantity");
        render(rareUpgradeMenu, session, "catali", "rare_upgrade");
        render(joinTeamMenu, session, "catali", "jointeam");
        render(assignLeaderMenu, session, "catali", "assign_leader");

        assertPreviewsExist("catali", "abandon");
        assertPreviewsExist("catali", "aipick");
        assertPreviewsExist("catali", "common_upgrade");
        assertPreviewsExist("catali", "upgrade_quantity");
        assertPreviewsExist("catali", "rare_upgrade");
        assertPreviewsExist("catali", "jointeam");
        assertPreviewsExist("catali", "assign_leader");
    }

    @Test
    void renderSandboxGamemode() {
        SandboxTeamMenu sandboxTeamMenu = new SandboxTeamMenu();
        Session session = MockSession.builder().locale("en").build();
        render(sandboxTeamMenu, session, "sandbox", "sandbox_team");

        assertPreviewsExist("sandbox", "sandbox_team");
    }

    @Test
    void renderHubFeature() {
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

        mockPreviewTexture("net-server-" + server1.getId(), Color.valueOf("1e3c72"), Color.valueOf("2a5298"));
        mockPreviewTexture("net-server-" + server2.getId(), Color.valueOf("b22222"), Color.valueOf("4a0e17"));

        ServerImageService stubImageService = new ServerImageService();
        GlobalServerListMenu globalServerListMenu = new GlobalServerListMenu(mockGateway, stubImageService);
        globalServerListMenu.setState(0);

        ServerListMenu serverListMenu = new ServerListMenu(mockGateway, stubImageService);
        serverListMenu.setState(0);

        ServerRedirectMenu serverRedirectMenu = new ServerRedirectMenu(mockGateway);
        serverRedirectMenu.setState(server1);

        Session session = MockSession.builder().locale("en").build();
        render(globalServerListMenu, session, "hub", "global_server_list");
        render(serverListMenu, session, "hub", "server_list");
        render(serverRedirectMenu, session, "hub", "server_redirect");

        assertPreviewsExist("hub", "global_server_list");
        assertPreviewsExist("hub", "server_list");
        assertPreviewsExist("hub", "server_redirect");
    }

    @Test
    void renderShopFeature() {
        ShopRegistry shopRegistry = new ShopRegistry(null, null, null) {
            @Override
            public List<ShopItem> getItems() {
                List<ShopItem> list = new ArrayList<>();
                list.add(new ShopItem() {
                    @Override public String getId() { return "pet_fire"; }
                    @Override public String getTitle(Session s) { return "Fire Pet Companion"; }
                    @Override public String getDescription(Session s) { return ""; }
                    @Override public long getPrice(Session s) { return 500; }
                    @Override public boolean isAvailable(Session s) { return true; }
                    @Override public boolean purchase(Session s) { return true; }
                });
                list.add(new ShopItem() {
                    @Override public String getId() { return "trail_rainbow"; }
                    @Override public String getTitle(Session s) { return "Rainbow Trail"; }
                    @Override public String getDescription(Session s) { return ""; }
                    @Override public long getPrice(Session s) { return 1000; }
                    @Override public boolean isAvailable(Session s) { return true; }
                    @Override public boolean purchase(Session s) { return true; }
                });
                return list;
            }
        };
        ShopMenu shopMenu = new ShopMenu(shopRegistry);
        GachaMenu gachaMenu = new GachaMenu(new GachaService(null, null));

        Session session = MockSession.builder().locale("en").build();
        render(shopMenu, session, "shop", "shop");
        render(gachaMenu, session, "shop", "gacha");

        assertPreviewsExist("shop", "shop");
        assertPreviewsExist("shop", "gacha");
    }

    @Test
    void renderTrailFeature() {
        TrailService trailService = new TrailService(null, null);
        Trail.create(trailService, "Fire Trail", 200, Fx.fire);
        Trail.create(trailService, "Lightning Spark", 500, Fx.lightningShoot);
        Trail.create(trailService, "Cosmic Glow", 1000, Fx.healWave);
        TrailMenu trailMenu = new TrailMenu(trailService, null);
        trailMenu.setState(0);

        Session session = MockSession.builder().locale("en").build();
        render(trailMenu, session, "trail", "trail");

        assertPreviewsExist("trail", "trail");
    }

    @Test
    void renderVoteFeature() {
        Map mapA = createMap("craters.msav", "Ancient Craters - Sector 146");
        Map mapB = createMap("ruins.msav", "Desolate Windswept Ruins");
        Seq<Map> maps = Seq.with(mapA, mapB);

        mockPreviewTexture("net-map-craters", Color.valueOf("2b580c"), Color.valueOf("639a67"));
        mockPreviewTexture("net-map-ruins", Color.valueOf("4a2810"), Color.valueOf("7c3f00"));

        RtvMenu rtvMenu = new RtvMenu(new RtvService(null, null) {
            @Override
            public Seq<Map> getMaps() {
                return maps;
            }

            @Override
            public boolean isVoted(Player player, String mapId) {
                return "craters".equals(mapId);
            }
        }, new MapRatingService((MapRatingRepository) null) {
            @Override
            public MapRatingStat getStat(Map map) {
                return new MapRatingStat(32, 4.88f);
            }
        });

        Player initiator = Player.create();
        initiator.name = "GoodPlayer";
        Player target = Player.create();
        target.name = "GrieferBot999";
        VoteKickSession voteSession = new VoteKickSession(target, initiator, "Griefing conveyor belts");
        VotePromptMenu votePromptMenu = new VotePromptMenu(new VoteKickService(null, null, null) {
            @Override
            public VoteKickSession getCurrentSession() {
                return voteSession;
            }

            @Override
            public int getVotes() {
                return 3;
            }

            @Override
            public int getVotesRequired() {
                return 5;
            }

            @Override
            public int getRemainingSeconds() {
                return 24;
            }
        });

        Session adminSession = MockSession.builder().locale("en").admin(true).build();
        render(rtvMenu, adminSession, "vote", "rtv");
        render(votePromptMenu, adminSession, "vote", "vote_prompt");

        assertPreviewsExist("vote", "rtv");
        assertPreviewsExist("vote", "vote_prompt");
    }

    @Test
    void renderDailyTaskFeature() {
        TaskMenu taskMenu = new TaskMenu(new DailyTaskService(null, null, null) {
            @Override
            public void checkRollover(Session session) {}

            @Override
            public List<DailyTask> getActiveTasks() {
                return List.of(
                        new DailyTask("task_1", "task.td_waves_title", "task.td_waves_desc", 30, 250, DailyTask.TaskType.SURVIVE_WAVES),
                        new DailyTask("task_2", "task.td_kills_title", "task.td_kills_desc", 500, 500, DailyTask.TaskType.KILL_UNITS)
                );
            }

            @Override
            public int getProgress(Session session, String taskId) {
                return "task_1".equals(taskId) ? 30 : 185;
            }
        });

        Session session = MockSession.builder().locale("en").build();
        render(taskMenu, session, "dailytask", "task");

        assertPreviewsExist("dailytask", "task");
    }

    @Test
    void renderMapRatingFeature() {
        Map sampleMap = createMap("craters.msav", "Ancient Craters");
        mockPreviewTexture("net-map-craters", Color.valueOf("2b580c"), Color.valueOf("639a67"));
        MapRatingService mapRatingService = new MapRatingService(null);
        RateMapMenu rateMapMenu = new RateMapMenu(mapRatingService);
        rateMapMenu.setState(sampleMap);

        Session session = MockSession.builder().locale("en").build();
        render(rateMapMenu, session, "maprating", "rate_map");

        assertPreviewsExist("maprating", "rate_map");
    }

    @Test
    void renderMapRecordFeature() {
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

        Session session = MockSession.builder().locale("en").build();
        render(mapRecordsMenu, session, "maprecord", "map_records");

        assertPreviewsExist("maprecord", "map_records");
    }

    @Test
    void renderSessionFeature() {
        LoginMenu loginMenu = new LoginMenu(new LoginService(null));

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
        PlayerInfoMenu playerInfoMenu = new PlayerInfoMenu(mockSessionService);

        Session session = MockSession.builder().locale("en").name("PlayerOne").build();
        render(loginMenu, session, "session", "login");
        render(playerInfoMenu, session, "session", "player_info");

        assertPreviewsExist("session", "login");
        assertPreviewsExist("session", "player_info");
    }

    @Test
    void renderGriefFeature() {
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

        GreifLoginMenu greifLoginMenu = new GreifLoginMenu();
        greifLoginMenu.setState("https://verify.mindustry-tool.com/auth");

        Session session = MockSession.builder().locale("en").name("Moderator").build();
        render(griefMenu, session, "grief", "grief");
        render(greifLoginMenu, session, "grief", "grief_login");

        assertPreviewsExist("grief", "grief");
        assertPreviewsExist("grief", "grief_login");
    }

    @Test
    void renderWelcomeFeature() {
        WelcomeMenu welcomeMenu = new WelcomeMenu();
        Session session = MockSession.builder().locale("en").build();
        render(welcomeMenu, session, "welcome", "welcome");

        assertPreviewsExist("welcome", "welcome");
    }
}
