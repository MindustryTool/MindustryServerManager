package plugin.session;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

public class SessionDataTest {

    @Test
    void fractionalIncrementsMatchSequentialFloatAddition() {
        float[] deltas = { 0.5f, 0.25f, 1.25f, 0.125f, 2.75f };
        SessionData data = new SessionData();

        float expected = 0f;
        for (int round = 0; round < 100; round++) {
            for (float delta : deltas) {
                data.addExp(delta);
                expected += delta;
            }
        }

        assertEquals(expected, data.exp);
    }

    @Test
    void concurrentIncrementsAreNotLost() throws Exception {
        int threads = 8;
        int incrementsPerThread = 5_000;
        float delta = 0.5f;

        SessionData data = new SessionData();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService executor = Executors.newFixedThreadPool(threads);

        try {
            for (int i = 0; i < threads; i++) {
                executor.execute(() -> {
                    try {
                        start.await();
                        for (int j = 0; j < incrementsPerThread; j++) {
                            data.addExp(delta);
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

        assertEquals(threads * incrementsPerThread * delta, data.exp);
    }
}
