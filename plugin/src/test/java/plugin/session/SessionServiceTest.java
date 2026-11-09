package plugin.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import plugin.menus.preview.MockSession;

public class SessionServiceTest {

    private SessionService service;

    @BeforeEach
    void setUp() {
        service = new SessionService(new SessionRepository(null), null, null);
    }

    @Test
    void updateLevelKeepsPlayerNameWhenLevelDoesNotChange() {
        Session session = MockSession.builder().name("Sentinel").locale("en").level(1).build();

        service.updateLevel(session);

        assertEquals(1, session.currentLevel);
        assertEquals("Sentinel", session.player.name);
    }

    @Test
    void updateLevelRefreshesPlayerNameOnLevelChange() {
        Session session = MockSession.builder().name("Sentinel").locale("en").level(1).build();
        session.getData().exp = 5000f;
        int expectedLevel = ExpUtils.levelFromTotalExp((long) session.getData().exp);

        service.updateLevel(session);

        assertEquals(expectedLevel, session.currentLevel);
        assertTrue(session.player.name.contains("Sentinel"));
    }

    @Test
    void concurrentExpGainKeepsExactTotal() throws Exception {
        int threads = 8;
        float gainsPerThread = 500f;

        Session session = MockSession.builder().name("Grinder").locale("en").level(1).build();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService executor = Executors.newFixedThreadPool(threads);

        try {
            for (int i = 0; i < threads; i++) {
                executor.execute(() -> {
                    try {
                        start.await();
                        for (int j = 0; j < gainsPerThread; j++) {
                            service.onExpGain(new ExpGainEvent(session, 1f));
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            done.await(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        float total = threads * gainsPerThread;
        assertEquals(total, session.getData().exp);

        service.updateLevel(session);
        assertEquals(ExpUtils.levelFromTotalExp((long) total), session.currentLevel);
    }
}
