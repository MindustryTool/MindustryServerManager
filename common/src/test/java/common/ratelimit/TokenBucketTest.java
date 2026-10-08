package common.ratelimit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class TokenBucketTest {

    private final AtomicLong clock = new AtomicLong();

    @Test
    void burstThenDeny() {
        TokenBucket bucket = new TokenBucket(5, 1, clock::get);

        for (int i = 0; i < 5; i++) {
            assertTrue(bucket.tryAcquire(), "burst acquire " + i);
        }

        assertFalse(bucket.tryAcquire());
    }

    @Test
    void refillsAfterElapsedTime() {
        TokenBucket bucket = new TokenBucket(5, 1, clock::get);

        for (int i = 0; i < 5; i++) {
            bucket.tryAcquire();
        }
        assertFalse(bucket.tryAcquire());

        clock.addAndGet(1_000_000_000L);

        assertTrue(bucket.tryAcquire());
    }

    @Test
    void partialRefillDoesNotGrantToken() {
        TokenBucket bucket = new TokenBucket(5, 1, clock::get);

        for (int i = 0; i < 5; i++) {
            bucket.tryAcquire();
        }

        clock.addAndGet(500_000_000L);

        assertFalse(bucket.tryAcquire());
    }

    @Test
    void tokensNeverExceedCapacity() {
        TokenBucket bucket = new TokenBucket(5, 1, clock::get);

        clock.addAndGet(10_000_000_000L);

        for (int i = 0; i < 5; i++) {
            assertTrue(bucket.tryAcquire());
        }
        assertFalse(bucket.tryAcquire());
    }
}
