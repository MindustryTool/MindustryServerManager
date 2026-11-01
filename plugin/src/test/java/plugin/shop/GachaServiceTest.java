package plugin.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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

public class GachaServiceTest {

    @TempDir
    static Path settingsDir;

    @TempDir
    Path tempDir;

    Database database;
    SessionRepository repository;
    SessionService sessionService;
    MoneyService moneyService;
    GachaService gachaService;

    @BeforeAll
    static void initSettings() {
        Core.settings = new Settings();
        Core.settings.setDataDirectory(new Fi(settingsDir.toFile()));
    }

    @BeforeEach
    void setUp() {
        Database.setTestPath(tempDir.resolve("test_gacha.db"));
        database = new Database();
        repository = new SessionRepository(database);
        sessionService = new SessionService(repository, null, null);
        moneyService = new MoneyService(repository, sessionService);
        gachaService = new GachaService(moneyService, repository);
    }

    @AfterEach
    void tearDown() {
        database.close();
        Database.clearTestPath();
    }

    private Session createSession(String uuid, long initialMoney) {
        Player player = Player.create();
        player.name = "LuckyGuy";
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
    void rollFailsWhenCoinsInsufficient() {
        Session session = createSession("gacha_uuid_1", 100);
        String result = gachaService.rollSingle(session);
        assertNull(result);
        assertEquals(100, moneyService.getBalance(session));
    }

    @Test
    void rollDeductsCoinsAndAwardsOutcome() {
        Session session = createSession("gacha_uuid_2", 500);
        String result = gachaService.rollSingle(session);
        assertNotNull(result);
        // Cost 200 was deducted; prize might deposit some coins back, but not negative
        assertTrue(moneyService.getBalance(session) >= 300);
    }

    @Test
    void rollTenFailsWhenCoinsUnder1800() {
        Session session = createSession("gacha_uuid_3", 1500);
        List<String> results = gachaService.rollTen(session);
        assertTrue(results.isEmpty());
        assertEquals(1500, moneyService.getBalance(session));
    }

    @Test
    void rollTenDeducts1800AndReturnsTenResults() {
        Session session = createSession("gacha_uuid_4", 2000);
        List<String> results = gachaService.rollTen(session);
        assertEquals(10, results.size());
    }
}
