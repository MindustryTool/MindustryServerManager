package plugin.trail;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import arc.Core;
import arc.Settings;
import arc.files.Fi;
import mindustry.gen.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import plugin.database.Database;
import plugin.money.MoneyService;
import plugin.session.Session;
import plugin.session.SessionData;
import plugin.session.SessionRepository;
import plugin.session.SessionService;

public class TrailServiceTest {

    @TempDir
    static Path settingsDir;

    @TempDir
    Path tempDir;

    Database database;
    SessionRepository repository;
    SessionService sessionService;
    MoneyService moneyService;
    TrailService trailService;

    @BeforeAll
    static void initSettings() {
        Core.settings = new Settings();
        Core.settings.setDataDirectory(new Fi(settingsDir.toFile()));
    }

    @BeforeEach
    void setUp() {
        Database.setTestPath(tempDir.resolve("test_trails.db"));
        database = new Database();
        repository = new SessionRepository(database);
        sessionService = new SessionService(repository);
        moneyService = new MoneyService(repository, sessionService);
        trailService = new TrailService(sessionService, repository);
        trailService.setMoneyService(moneyService);

        trailService.init();
    }

    @AfterEach
    void tearDown() {
        database.close();
        Database.clearTestPath();
    }

    private Session createSession(String uuid, long initialMoney) {
        Player player = Player.create();
        player.name = "TrailRunner";
        player.locale = "en";
        try {
            var field = player.getClass().getField("uuid");
            field.set(player, uuid);
        } catch (Exception ignored) {
        }
        SessionData data = new SessionData();
        data.money = initialMoney;
        data.unlockedTrails = new ArrayList<>();
        return new Session(player, data);
    }

    @Test
    void unownedTrailRequiresPurchase() {
        Session session = createSession("trail_uuid_1", 100);
        TrailService.Trail shock = trailService.trails.get("shock");
        assertFalse(shock.owns(session));
    }

    @Test
    void purchaseFailsWhenCoinsInsufficient() {
        Session session = createSession("trail_uuid_2", 100);
        TrailService.Trail shock = trailService.trails.get("shock"); // 500 coins

        boolean bought = trailService.purchase(session, shock);
        assertFalse(bought);
        assertFalse(shock.owns(session));
    }

    @Test
    void purchaseSucceedsWhenCoinsSufficient() {
        Session session = createSession("trail_uuid_3", 600);
        TrailService.Trail shock = trailService.trails.get("shock"); // 500 coins

        boolean bought = trailService.purchase(session, shock);
        assertTrue(bought);
        assertTrue(shock.owns(session));
        assertTrue(session.getData().unlockedTrails.contains("shock"));
    }

    @Test
    void trailsAreFreeWhenMoneyServiceNotPresent() {
        trailService.setMoneyService(null);
        Session session = createSession("trail_uuid_non_official", 0);
        TrailService.Trail shock = trailService.trails.get("shock");
        assertTrue(shock.owns(session));
    }
}
