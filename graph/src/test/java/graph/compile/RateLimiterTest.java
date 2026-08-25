package graph.compile;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimiterTest {

    @Test
    void burstThenThrottles() {
        AtomicLong now = new AtomicLong(0);
        RateLimiter limiter = new RateLimiter(1.0, 3, () -> now.get());

        assertTrue(limiter.tryAcquire("g"));
        assertTrue(limiter.tryAcquire("g"));
        assertTrue(limiter.tryAcquire("g"));
        assertFalse(limiter.tryAcquire("g"), "burst exhausted");
    }

    @Test
    void refillsOverTime() {
        AtomicLong now = new AtomicLong(0);
        RateLimiter limiter = new RateLimiter(10.0, 2, () -> now.get());

        assertTrue(limiter.tryAcquire("k"));
        assertTrue(limiter.tryAcquire("k"));
        assertFalse(limiter.tryAcquire("k"));

        now.set(500);
        assertTrue(limiter.tryAcquire("k"), "5 permits accrued over 500ms at 10/s");
    }

    @Test
    void keysAreIndependent() {
        RateLimiter limiter = new RateLimiter(1.0, 1, () -> 0);
        assertTrue(limiter.tryAcquire("graphA"));
        assertFalse(limiter.tryAcquire("graphA"));
        assertTrue(limiter.tryAcquire("graphB"));
    }

    @Test
    void invalidConfigRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RateLimiter(0, 1, () -> 0));
        assertThrows(IllegalArgumentException.class, () -> new RateLimiter(-5, 1, () -> 0));
    }
}
