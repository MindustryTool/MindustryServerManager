package plugin.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import java.nio.file.Path;
import arc.Core;
import arc.Settings;
import arc.files.Fi;
import mindustry.content.UnitTypes;
import mindustry.game.EventType.WaveEvent;
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

public class PetServiceTest {

    @TempDir
    static Path settingsDir;

    @TempDir
    Path tempDir;

    Database database;
    SessionRepository repository;
    SessionService sessionService;
    MoneyService moneyService;
    PetService petService;

    @BeforeAll
    static void initSettings() {
        Core.settings = new Settings();
        Core.settings.setDataDirectory(new Fi(settingsDir.toFile()));
    }

    @BeforeEach
    void setUp() {
        Database.setTestPath(tempDir.resolve("test_pets.db"));
        database = new Database();
        repository = new SessionRepository(database);
        sessionService = new SessionService(repository, null, null);
        moneyService = new MoneyService(repository, sessionService);
        petService = new PetService(moneyService);
    }

    @AfterEach
    void tearDown() {
        database.close();
        Database.clearTestPath();
    }

    private Session createSession(String uuid, long initialMoney) {
        Player player = Player.create();
        player.name = "PetMaster";
        player.locale = "en";
        try {
            var field = player.getClass().getField("uuid");
            field.set(player, uuid);
        } catch (Exception ignored) {
        }
        SessionData data = new SessionData();
        data.money = initialMoney;
        return new Session(player, data);
    }

    @Test
    void summonFailsWhenCoinsInsufficient() {
        Session session = createSession("pet_uuid_1", 100);
        // Poly costs 400
        boolean success = petService.summonPet(session, UnitTypes.poly);
        assertFalse(success);
    }

    @Test
    void waveResetClearsSummonCounts() {
        Session session = createSession("pet_uuid_wave", 2000);
        assertEquals(0, petService.getWaveSummonCount(session));
        // Simulate wave event
        petService.onWave(new WaveEvent());
        assertEquals(0, petService.getWaveSummonCount(session));
    }
}
