package plugin.menus.preview;

import java.nio.file.Files;
import java.nio.file.Path;
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
import plugin.gamemode.catali.data.CataliTeamData;
import plugin.gamemode.catali.menu.AIPickMenu;
import plugin.maprating.MapRatingRepository;
import plugin.maprating.MapRatingService;
import plugin.maprating.MapRatingStat;
import plugin.session.Session;
import plugin.vote.RtvMenu;
import plugin.vote.RtvService;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerateMenuPreviewsTest {

    private static final Path OUTPUT_DIR = Path.of("build", "menu-previews");

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
            if (map.name().contains("Craters")) {
                return new MapRatingStat(48, 4.92f);
            }
            if (map.name().contains("Ruins")) {
                return new MapRatingStat(19, 4.25f);
            }
            return new MapRatingStat(7, 3.80f);
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
                    new DailyTask("task_1", "task.survive_waves_title", "task.survive_waves_desc", 30, 250, DailyTask.TaskType.SURVIVE_WAVES),
                    new DailyTask("task_2", "task.kill_units_title", "task.kill_units_desc", 500, 500, DailyTask.TaskType.KILL_UNITS),
                    new DailyTask("task_3", "task.build_blocks_title", "task.build_blocks_desc", 200, 150, DailyTask.TaskType.BUILD_BLOCKS)
            );
        }

        @Override
        public int getProgress(Session session, String taskId) {
            return switch (taskId) {
                case "task_1" -> 30;
                case "task_2" -> 342;
                default -> 80;
            };
        }
    }

    @Test
    void generateRtvMenuPreviews() throws Exception {
        Map mapA = createMap("craters.msav", "Ancient Craters");
        Map mapB = createMap("ruins.msav", "Desolate Ruins");
        Map mapC = createMap("glacier.msav", "Frozen Glacier");
        Seq<Map> maps = Seq.with(mapA, mapB, mapC);

        RtvMenu menuEn = new RtvMenu(new StubRtvService(maps), new StubMapRatingService());
        Session sessionEn = MockSession.builder().locale("en").name("DevTester").build();

        Path tightEn = OUTPUT_DIR.resolve("rtv_menu_tight_en.png");
        MenuSnapshot.builder()
                .menu(menuEn)
                .session(sessionEn)
                .fullScreen(false)
                .capture(tightEn);

        Path fullEn = OUTPUT_DIR.resolve("rtv_menu_fullscreen_en.png");
        MenuSnapshot.builder()
                .menu(menuEn)
                .session(sessionEn)
                .fullScreen(true)
                .capture(fullEn);

        RtvMenu menuVi = new RtvMenu(new StubRtvService(maps), new StubMapRatingService());
        Session sessionVi = MockSession.builder().locale("vi").name("NguoiChoi").build();

        Path tightVi = OUTPUT_DIR.resolve("rtv_menu_tight_vi.png");
        MenuSnapshot.builder()
                .menu(menuVi)
                .session(sessionVi)
                .fullScreen(false)
                .capture(tightVi);

        assertTrue(Files.exists(tightEn));
        assertTrue(Files.exists(fullEn));
        assertTrue(Files.exists(tightVi));
    }

    @Test
    void generateDailyTaskMenuPreview() throws Exception {
        TaskMenu taskMenu = new TaskMenu(new StubDailyTaskService());
        Session session = MockSession.builder().locale("en").name("TaskHero").build();

        Path tight = OUTPUT_DIR.resolve("task_menu_tight.png");
        MenuSnapshot.builder()
                .menu(taskMenu)
                .session(session)
                .fullScreen(false)
                .capture(tight);

        assertTrue(Files.exists(tight));
    }

    @Test
    void generateAIPickMenuPreview() throws Exception {
        AIPickMenu aiMenu = new AIPickMenu();
        CataliTeamData team = new CataliTeamData();
        team.useCommandAi = false;
        aiMenu.setState(team);

        Session session = MockSession.builder().locale("en").name("Commander").build();

        Path tight = OUTPUT_DIR.resolve("aipick_menu_tight.png");
        MenuSnapshot.builder()
                .menu(aiMenu)
                .session(session)
                .fullScreen(false)
                .capture(tight);

        assertTrue(Files.exists(tight));
    }
}
