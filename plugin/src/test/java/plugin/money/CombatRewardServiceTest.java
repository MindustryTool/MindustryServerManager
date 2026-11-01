package plugin.money;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import arc.Core;
import arc.Settings;
import arc.files.Fi;
import mindustry.Vars;
import mindustry.core.GameState;
import mindustry.game.EventType.WaveEvent;
import mindustry.gen.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import plugin.database.Database;
import plugin.session.Session;
import plugin.session.SessionData;
import plugin.session.SessionRepository;
import plugin.session.SessionService;

public class CombatRewardServiceTest {

    @TempDir
    static Path settingsDir;

    @TempDir
    Path tempDir;

    Database database;
    SessionRepository repository;
    SessionService sessionService;
    MoneyService moneyService;
    CombatRewardService combatRewardService;

    @BeforeAll
    static void initSettings() {
        Core.settings = new Settings();
        Core.settings.setDataDirectory(new Fi(settingsDir.toFile()));
    }

    @BeforeEach
    void setUp() {
        Database.setTestPath(tempDir.resolve("test_combat.db"));
        database = new Database();
        repository = new SessionRepository(database);
        sessionService = new SessionService(repository, null, null);
        moneyService = new MoneyService(repository, sessionService);
        combatRewardService = new CombatRewardService(moneyService, sessionService);

        Vars.state = new GameState();
        Vars.state.wave = 5;
    }

    @AfterEach
    void tearDown() {
        database.close();
        Database.clearTestPath();
    }

    private Session createSession(String uuid, boolean afk) {
        Player player = Player.create();
        player.name = "TestPlayer";
        player.locale = "en";
        try {
            var field = player.getClass().getField("uuid");
            field.set(player, uuid);
        } catch (Exception ignored) {
        }
        SessionData data = new SessionData();
        Session session = new Session(player, data);
        session.afkState = afk ? Session.AfkState.AFK : Session.AfkState.ACTIVE;
        sessionService.get().put(uuid, session);
        return session;
    }

    @Test
    void waveClearGrantsCoinsSilentlyToActivePlayers() {
        Session active = createSession("uuid_active", false);
        Session afk = createSession("uuid_afk", true);

        combatRewardService.onWave(new WaveEvent());

        // Wave 5 reward: Math.max(10, 10 + 5 * 2) = 20
        assertEquals(20, moneyService.getBalance(active));
        assertEquals(0, moneyService.getBalance(afk));
    }

    @Test
    void maxKillRewardIsCappedAtFifty() {
        assertEquals(50L, CombatRewardService.MAX_KILL_REWARD);
    }
}
