package plugin.dailytask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import arc.Core;
import arc.Settings;
import arc.files.Fi;
import mindustry.Vars;
import mindustry.gen.Player;
import mindustry.net.Net;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import plugin.database.Database;
import plugin.gamemode.Gamemode;
import plugin.money.MoneyService;
import plugin.session.Session;
import plugin.session.SessionData;
import plugin.session.SessionRepository;
import plugin.session.SessionService;

public class DailyTaskServiceTest {

    @TempDir
    static Path settingsDir;

    @TempDir
    Path tempDir;

    Database database;
    SessionRepository repository;
    SessionService sessionService;
    MoneyService moneyService;
    DailyTaskService dailyTaskService;

    @BeforeAll
    static void initSettings() {
        Core.settings = new Settings();
        Core.settings.setDataDirectory(new Fi(settingsDir.toFile()));
    }

    @BeforeEach
    void setUp() {
        if (Vars.net == null) {
            Vars.net = new Net(null);
        }
        Database.setTestPath(tempDir.resolve("test_tasks.db"));
        database = new Database();
        repository = new SessionRepository(database);
        sessionService = new SessionService(repository, null, null);
        moneyService = new MoneyService(repository, sessionService);
        dailyTaskService = new DailyTaskService(moneyService, repository, sessionService);
    }

    @AfterEach
    void tearDown() {
        database.close();
        Database.clearTestPath();
        Core.settings.remove(Gamemode.KEY);
    }

    private Session createSession(String uuid) {
        Player player = Player.create();
        player.name = "Hero";
        player.locale = "en";
        try {
            var field = player.getClass().getField("uuid");
            field.set(player, uuid);
        } catch (Exception ignored) {
        }
        SessionData data = new SessionData();
        return new Session(player, data);
    }

    @Test
    void activeTasksReflectCurrentGamemode() {
        Gamemode.set("TowerDefense");
        List<DailyTask> tdTasks = dailyTaskService.getActiveTasks();
        assertTrue(tdTasks.stream().anyMatch(t -> t.getId().equals("td_waves")));

        Gamemode.set("Flood");
        List<DailyTask> floodTasks = dailyTaskService.getActiveTasks();
        assertTrue(floodTasks.stream().anyMatch(t -> t.getId().equals("flood_waves")));

        Gamemode.set("");
        List<DailyTask> defaultTasks = dailyTaskService.getActiveTasks();
        assertTrue(defaultTasks.stream().anyMatch(t -> t.getId().equals("gen_waves")));
    }

    @Test
    void rolloverResetsProgressOnNewDay() {
        Session session = createSession("uuid_rollover");
        session.getData().dailyTaskDate = "2020-01-01";
        session.getData().dailyTaskProgress.put("td_waves", 10);

        dailyTaskService.checkRollover(session);

        assertEquals(LocalDate.now().toString(), session.getData().dailyTaskDate);
        assertEquals(0, dailyTaskService.getProgress(session, "td_waves"));
    }

    @Test
    void addProgressIncrementsAndAwardsCoinsOnGoal() {
        Gamemode.set("TowerDefense");
        Session session = createSession("uuid_progress");
        dailyTaskService.checkRollover(session);

        // td_turrets goal is 10, reward is 200
        dailyTaskService.addProgress(session, DailyTask.TaskType.BUILD_BLOCKS, 5);
        assertEquals(5, dailyTaskService.getProgress(session, "td_turrets"));
        assertEquals(0, moneyService.getBalance(session));

        dailyTaskService.addProgress(session, DailyTask.TaskType.BUILD_BLOCKS, 5);
        assertEquals(10, dailyTaskService.getProgress(session, "td_turrets"));
        assertEquals(200, moneyService.getBalance(session));

        // Excess progress does not grant duplicate rewards
        dailyTaskService.addProgress(session, DailyTask.TaskType.BUILD_BLOCKS, 5);
        assertEquals(200, moneyService.getBalance(session));
    }

    @Test
    void resetTasksClearsDailyProgress() {
        Session session = createSession("uuid_reset");
        dailyTaskService.checkRollover(session);
        session.getData().dailyTaskProgress.put("td_waves", 12);
        assertEquals(12, dailyTaskService.getProgress(session, "td_waves"));

        dailyTaskService.resetTasks(session);
        assertEquals(0, dailyTaskService.getProgress(session, "td_waves"));
    }

    @Test
    void buildDebounceConfigured() {
        assertEquals(500L, DailyTaskService.BUILD_DEBOUNCE_MILLIS);
    }
}
