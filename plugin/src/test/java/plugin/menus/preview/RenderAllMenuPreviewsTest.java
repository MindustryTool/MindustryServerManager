package plugin.menus.preview;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import plugin.dailytask.DailyTask;
import plugin.dailytask.DailyTaskService;
import plugin.dailytask.TaskMenu;
import plugin.gamemode.catali.data.CataliCommonUpgrade;
import plugin.gamemode.catali.data.CataliTeamData;
import plugin.gamemode.catali.menu.AIPickMenu;
import plugin.gamemode.catali.menu.AbandonMenu;
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

class RenderAllMenuPreviewsTest {

    private static final Path BASE_DIR = Path.of("build", "menu-previews");
    private static final Path BATCH1_DIR = BASE_DIR.resolve("batch1");
    private static final Path BATCH2_DIR = BASE_DIR.resolve("batch2");
    private static final Path BATCH3_DIR = BASE_DIR.resolve("batch3");

    @BeforeAll
    static void ensureDirectories() throws IOException {
        Files.createDirectories(BATCH1_DIR);
        Files.createDirectories(BATCH2_DIR);
        Files.createDirectories(BATCH3_DIR);
    }

    private static Map createMap(String filename, String name) {
        StringMap tags = new StringMap();
        tags.put("name", name);
        return new Map(new Fi(filename), 100, 100, tags, true, 0, 146);
    }

    private static void renderAllViewports(plugin.menus.ServerMenu<?> menu, Session session, Path targetPath) {
        for (MenuViewport vp : MenuViewport.values()) {
            Session s = MockSession.builder()
                    .locale("en")
                    .mobile(vp.mobile)
                    .admin(session != null && session.player != null && session.player.admin)
                    .build();
            MenuSnapshot.builder().menu(menu).session(s).viewport(vp).capture(targetPath);
        }
    }

    @Test
    void renderBatch1() {
        Map mapA = createMap("craters.msav", "Ancient Craters - Sector 146");
        Map mapB = createMap("ruins.msav", "Desolate Windswept Ruins");
        Seq<Map> maps = Seq.with(mapA, mapB);

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

        WelcomeMenu welcomeMenu = new WelcomeMenu();
        LoginMenu loginMenu = new LoginMenu(new LoginService(null));

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

        Session adminSession = MockSession.builder().locale("en").admin(true).build();
        renderAllViewports(rtvMenu, adminSession, BATCH1_DIR.resolve("rtv.png"));
        renderAllViewports(taskMenu, adminSession, BATCH1_DIR.resolve("task.png"));
        renderAllViewports(votePromptMenu, adminSession, BATCH1_DIR.resolve("votekick.png"));
        renderAllViewports(welcomeMenu, adminSession, BATCH1_DIR.resolve("welcome.png"));
        renderAllViewports(loginMenu, adminSession, BATCH1_DIR.resolve("login.png"));
        renderAllViewports(shopMenu, adminSession, BATCH1_DIR.resolve("shop.png"));
        renderAllViewports(gachaMenu, adminSession, BATCH1_DIR.resolve("gacha.png"));

        assertTrue(Files.exists(BATCH1_DIR.resolve("rtv-desktop.png")));
        assertTrue(Files.exists(BATCH1_DIR.resolve("rtv-mobile-landscape.png")));
        assertTrue(Files.exists(BATCH1_DIR.resolve("rtv-mobile-portrait.png")));
        assertTrue(Files.exists(BATCH1_DIR.resolve("login-desktop.png")));
        assertTrue(Files.exists(BATCH1_DIR.resolve("shop-desktop.png")));
        assertTrue(Files.exists(BATCH1_DIR.resolve("gacha-desktop.png")));
    }

    @Test
    void renderBatch2() {
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
        SandboxTeamMenu sandboxTeamMenu = new SandboxTeamMenu();

        Session session = MockSession.builder().locale("en").build();
        renderAllViewports(abandonMenu, session, BATCH2_DIR.resolve("abandon.png"));
        renderAllViewports(aiPickMenu, session, BATCH2_DIR.resolve("aipick.png"));
        renderAllViewports(commonUpgradeMenu, session, BATCH2_DIR.resolve("common_upgrade.png"));
        renderAllViewports(quantityMenu, session, BATCH2_DIR.resolve("upgrade_quantity.png"));
        renderAllViewports(rareUpgradeMenu, session, BATCH2_DIR.resolve("rare_upgrade.png"));
        renderAllViewports(joinTeamMenu, session, BATCH2_DIR.resolve("jointeam.png"));
        renderAllViewports(sandboxTeamMenu, session, BATCH2_DIR.resolve("sandbox_team.png"));

        assertTrue(Files.exists(BATCH2_DIR.resolve("sandbox_team-desktop.png")));
        assertTrue(Files.exists(BATCH2_DIR.resolve("sandbox_team-mobile-landscape.png")));
        assertTrue(Files.exists(BATCH2_DIR.resolve("sandbox_team-mobile-portrait.png")));
    }

    @Test
    void renderBatch3() {
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
        Trail.create(trailService, "Fire Trail", 200, Fx.fire);
        Trail.create(trailService, "Lightning Spark", 500, Fx.lightningShoot);
        Trail.create(trailService, "Cosmic Glow", 1000, Fx.healWave);
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

        Session session = MockSession.builder().locale("en").name("Moderator").build();
        renderAllViewports(globalServerListMenu, session, BATCH3_DIR.resolve("global_server_list.png"));
        renderAllViewports(serverListMenu, session, BATCH3_DIR.resolve("server_list.png"));
        renderAllViewports(serverRedirectMenu, session, BATCH3_DIR.resolve("server_redirect.png"));
        renderAllViewports(rateMapMenu, session, BATCH3_DIR.resolve("rate_map.png"));
        renderAllViewports(mapRecordsMenu, session, BATCH3_DIR.resolve("map_records.png"));
        renderAllViewports(trailMenu, session, BATCH3_DIR.resolve("trail.png"));
        renderAllViewports(griefMenu, session, BATCH3_DIR.resolve("grief.png"));
        renderAllViewports(playerInfoMenu, session, BATCH3_DIR.resolve("player_info.png"));
        renderAllViewports(greifLoginMenu, session, BATCH3_DIR.resolve("grief_login.png"));

        assertTrue(Files.exists(BATCH3_DIR.resolve("global_server_list-desktop.png")));
        assertTrue(Files.exists(BATCH3_DIR.resolve("player_info-desktop.png")));
        assertTrue(Files.exists(BATCH3_DIR.resolve("player_info-mobile-portrait.png")));
    }
}
