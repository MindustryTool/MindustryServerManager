package common.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class KeyedRateLimiterTest {

    private final AtomicLong clock = new AtomicLong();

    @Test
    void keysAreIsolated() {
        KeyedRateLimiter<String> limiter = new KeyedRateLimiter<>(5, 1, Duration.ofMinutes(10), clock::get);

        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire("a"));
        }
        assertFalse(limiter.tryAcquire("a"));

        assertTrue(limiter.tryAcquire("b"));
    }

    @Test
    void idleKeysAreEvictedAutomatically() {
        KeyedRateLimiter<String> limiter = new KeyedRateLimiter<>(5, 1, Duration.ofSeconds(5), clock::get);

        for (int i = 0; i < 5; i++) {
            limiter.tryAcquire("a");
        }
        assertFalse(limiter.tryAcquire("a"));
        assertEquals(1, limiter.size());

        clock.addAndGet(10_000_000_000L);
        assertTrue(limiter.tryAcquire("b"));

        assertEquals(1, limiter.size(), "idle key 'a' must be evicted during the sweep");
        assertTrue(limiter.tryAcquire("a"), "evicted key starts with a full bucket");
    }

    @Test
    void explicitEviction() {
        KeyedRateLimiter<String> limiter = new KeyedRateLimiter<>(5, 1, Duration.ofMinutes(10), clock::get);

        limiter.tryAcquire("a");
        assertEquals(1, limiter.size());

        clock.addAndGet(10_000_000_000L);
        limiter.evictIdle(Duration.ofSeconds(5));

        assertEquals(0, limiter.size());
    }

    @Test
    void concurrentAcquisitionsDoNotExceedCapacity() throws InterruptedException {
        KeyedRateLimiter<String> limiter = new KeyedRateLimiter<>(5, 0);
        int threads = 16;
        AtomicInteger successes = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    if (limiter.tryAcquire("k")) {
                        successes.incrementAndGet();
                    }
                    return null;
                });
            }

            ready.await();
            go.countDown();
            pool.shutdown();

            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertEquals(5, successes.get());
    }
}
