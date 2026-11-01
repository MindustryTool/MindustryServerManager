package plugin.money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
import plugin.session.Session;
import plugin.session.SessionData;
import plugin.session.SessionRepository;
import plugin.session.SessionService;

public class MoneyServiceTest {

    @TempDir
    static Path settingsDir;

    @TempDir
    Path tempDir;

    Database database;
    SessionRepository repository;
    SessionService sessionService;
    MoneyService moneyService;

    @BeforeAll
    static void initSettings() {
        Core.settings = new Settings();
        Core.settings.setDataDirectory(new Fi(settingsDir.toFile()));
    }

    @BeforeEach
    void setUp() {
        Database.setTestPath(tempDir.resolve("test_money.db"));
        database = new Database();
        repository = new SessionRepository(database);
        sessionService = new SessionService(repository);
        moneyService = new MoneyService(repository, sessionService);
    }

    @AfterEach
    void tearDown() {
        database.close();
        Database.clearTestPath();
    }

    private Session createSession(String uuid, long initialMoney) {
        Player player = Player.create();
        player.name = "Tester";
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
    void getBalanceReturnsAccurateAmount() {
        Session session = createSession("uuid_1", 250);
        assertEquals(250, moneyService.getBalance(session));
    }

    @Test
    void depositIncreasesBalance() {
        Session session = createSession("uuid_2", 100);
        moneyService.deposit(session, 150);
        assertEquals(250, moneyService.getBalance(session));
    }

    @Test
    void depositIgnoresZeroOrNegativeAmount() {
        Session session = createSession("uuid_3", 100);
        moneyService.deposit(session, 0);
        moneyService.deposit(session, -50);
        assertEquals(100, moneyService.getBalance(session));
    }

    @Test
    void withdrawSucceedsWhenFundsAreSufficient() {
        Session session = createSession("uuid_4", 500);
        boolean success = moneyService.withdraw(session, 300);
        assertTrue(success);
        assertEquals(200, moneyService.getBalance(session));
    }

    @Test
    void withdrawFailsWhenFundsAreInsufficient() {
        Session session = createSession("uuid_5", 100);
        boolean success = moneyService.withdraw(session, 150);
        assertFalse(success);
        assertEquals(100, moneyService.getBalance(session));
    }

    @Test
    void concurrentDepositsMaintainConsistency() throws InterruptedException {
        Session session = createSession("uuid_concurrent", 0);
        int threadCount = 20;
        int perThreadDeposits = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < perThreadDeposits; j++) {
                        moneyService.deposit(session, 10);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        assertEquals(threadCount * perThreadDeposits * 10L, moneyService.getBalance(session));
    }

    @Test
    void depositClampsToMaxBalance() {
        Session session = createSession("uuid_overflow", MoneyService.MAX_BALANCE - 100);
        moneyService.deposit(session, 500);
        assertEquals(MoneyService.MAX_BALANCE, moneyService.getBalance(session));
    }

    @Test
    void setBalanceDirectlyModifiesWallet() {
        Session session = createSession("uuid_set", 100);
        moneyService.setBalance(session, 5000);
        assertEquals(5000, moneyService.getBalance(session));

        moneyService.setBalance(session, -50);
        assertEquals(0, moneyService.getBalance(session));
    }
}
