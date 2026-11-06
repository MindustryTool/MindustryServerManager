package plugin.menus.preview;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import arc.files.Fi;
import arc.struct.Seq;
import arc.struct.StringMap;
import mindustry.gen.Player;
import mindustry.maps.Map;
import org.junit.jupiter.api.Test;
import plugin.dailytask.DailyTask;
import plugin.dailytask.DailyTaskService;
import plugin.dailytask.TaskMenu;
import plugin.maprating.MapRatingRepository;
import plugin.maprating.MapRatingService;
import plugin.maprating.MapRatingStat;
import plugin.session.LoginMenu;
import plugin.session.LoginService;
import plugin.session.Session;
import plugin.shop.GachaMenu;
import plugin.shop.GachaService;
import plugin.shop.ShopItem;
import plugin.shop.ShopMenu;
import plugin.shop.ShopRegistry;
import plugin.vote.RtvMenu;
import plugin.vote.RtvService;
import plugin.vote.VoteKickService;
import plugin.vote.VotePromptMenu;
import plugin.welcome.WelcomeMenu;

import static org.junit.jupiter.api.Assertions.assertTrue;

class Batch1MenuPreviewsTest {

    private static final Path OUTPUT_DIR = Path.of("build", "menu-previews", "batch1");

    private Map createMap(String filename, String name) {
        StringMap tags = new StringMap();
        tags.put("name", name);
        return new Map(new Fi(filename), 100, 100, tags, true, 0, 146);
    }

    private static class StubRtvService extends RtvService {
        private final Seq<Map> maps;

        StubRtvService(Seq<Map> maps) {
            super(null, null);
            this.maps = maps;
        }

        @Override
        public Seq<Map> getMaps() {
            return maps;
        }

        @Override
        public boolean isVoted(Player player, String mapId) {
            return "craters".equals(mapId);
        }
    }

    private static class StubMapRatingService extends MapRatingService {
        StubMapRatingService() {
            super((MapRatingRepository) null);
        }

        @Override
        public MapRatingStat getStat(Map map) {
            return new MapRatingStat(32, 4.88f);
        }
    }

    private static class StubDailyTaskService extends DailyTaskService {
        StubDailyTaskService() {
            super(null, null, null);
        }

        @Override
        public void checkRollover(Session session) {
        }

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
    }

    private static class StubVoteKickService extends VoteKickService {
        private final VoteKickSession session;

        StubVoteKickService(VoteKickSession session) {
            super(null, null, null);
            this.session = session;
        }

        @Override
        public VoteKickSession getCurrentSession() {
            return session;
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
    }

    private record StubShopItem(String id, String title, long price) implements ShopItem {
        @Override public String getId() { return id; }
        @Override public String getTitle(Session s) { return title; }
        @Override public String getDescription(Session s) { return ""; }
        @Override public long getPrice(Session s) { return price; }
        @Override public boolean isAvailable(Session s) { return true; }
        @Override public boolean purchase(Session s) { return true; }
    }

    private static class StubShopRegistry extends ShopRegistry {
        StubShopRegistry() {
            super(null, null, null);
        }

        @Override
        public List<ShopItem> getItems() {
            List<ShopItem> list = new ArrayList<>();
            list.add(new StubShopItem("pet_fire", "Fire Pet Companion", 500));
            list.add(new StubShopItem("trail_rainbow", "Rainbow Trail", 1000));
            return list;
        }
    }

    @Test
    void renderBatch1MenusInAllViewports() throws Exception {
        Map mapA = createMap("craters.msav", "Ancient Craters - Sector 146");
        Map mapB = createMap("ruins.msav", "Desolate Windswept Ruins");
        Seq<Map> maps = Seq.with(mapA, mapB);

        RtvMenu rtvMenu = new RtvMenu(new StubRtvService(maps), new StubMapRatingService());
        TaskMenu taskMenu = new TaskMenu(new StubDailyTaskService());

        Player initiator = Player.create();
        initiator.name = "GoodPlayer";
        Player target = Player.create();
        target.name = "GrieferBot999";
        VoteKickService.VoteKickSession voteSession = new VoteKickService.VoteKickSession(initiator, target, "Griefing conveyor belts");
        VotePromptMenu votePromptMenu = new VotePromptMenu(new StubVoteKickService(voteSession));

        WelcomeMenu welcomeMenu = new WelcomeMenu();
        LoginMenu loginMenu = new LoginMenu(new LoginService(null));
        ShopMenu shopMenu = new ShopMenu(new StubShopRegistry());
        GachaMenu gachaMenu = new GachaMenu(new GachaService(null, null));

        MenuViewport[] viewports = {
                MenuViewport.DESKTOP,
                MenuViewport.MOBILE_LANDSCAPE,
                MenuViewport.MOBILE_PORTRAIT
        };

        for (MenuViewport vp : viewports) {
            String suffix = "_" + vp.name().toLowerCase() + ".png";
            Session session = MockSession.builder().locale("en").mobile(vp.mobile).admin(true).build();

            MenuSnapshot.builder().menu(rtvMenu).session(session).viewport(vp).capture(OUTPUT_DIR.resolve("rtv" + suffix));
            MenuSnapshot.builder().menu(taskMenu).session(session).viewport(vp).capture(OUTPUT_DIR.resolve("task" + suffix));
            MenuSnapshot.builder().menu(votePromptMenu).session(session).viewport(vp).capture(OUTPUT_DIR.resolve("votekick" + suffix));
            MenuSnapshot.builder().menu(welcomeMenu).session(session).viewport(vp).capture(OUTPUT_DIR.resolve("welcome" + suffix));
            MenuSnapshot.builder().menu(loginMenu).session(session).viewport(vp).capture(OUTPUT_DIR.resolve("login" + suffix));
            MenuSnapshot.builder().menu(shopMenu).session(session).viewport(vp).capture(OUTPUT_DIR.resolve("shop" + suffix));
            MenuSnapshot.builder().menu(gachaMenu).session(session).viewport(vp).capture(OUTPUT_DIR.resolve("gacha" + suffix));
        }

        assertTrue(Files.exists(OUTPUT_DIR.resolve("rtv_mobile_landscape.png")));
        assertTrue(Files.exists(OUTPUT_DIR.resolve("rtv_mobile_portrait.png")));
        assertTrue(Files.exists(OUTPUT_DIR.resolve("task_mobile_landscape.png")));
        assertTrue(Files.exists(OUTPUT_DIR.resolve("task_mobile_portrait.png")));
        assertTrue(Files.exists(OUTPUT_DIR.resolve("votekick_mobile_landscape.png")));
        assertTrue(Files.exists(OUTPUT_DIR.resolve("votekick_mobile_portrait.png")));
        assertTrue(Files.exists(OUTPUT_DIR.resolve("shop_mobile_portrait.png")));
        assertTrue(Files.exists(OUTPUT_DIR.resolve("gacha_mobile_portrait.png")));
    }
}
