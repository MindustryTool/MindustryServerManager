package plugin.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class IdleTickThrottlerTest {

    private IdleTickThrottler throttler;

    @BeforeEach
    void setUp() {
        throttler = new IdleTickThrottler();
    }

    @Test
    void defaultSleepDurationIs100Ms() {
        assertEquals(IdleTickThrottler.DEFAULT_THROTTLE_SLEEP_MS, throttler.getThrottleSleepMs());
        assertEquals(100L, throttler.getThrottleSleepMs());
    }

    @Test
    void throttlesWhenPausedAndEmpty() {
        boolean shouldThrottle = throttler.shouldThrottle(true, 0);
        assertTrue(shouldThrottle, "Expected throttling when paused with no players");
    }

    @Test
    void doesNotThrottleWhenPlaying() {
        boolean shouldThrottle = throttler.shouldThrottle(false, 0);
        assertFalse(shouldThrottle, "Expected no throttling while game is playing/active");
    }

    @Test
    void doesNotThrottleWhenPlayersPresent() {
        boolean shouldThrottle = throttler.shouldThrottle(true, 2);
        assertFalse(shouldThrottle, "Expected no throttling when players are in the game");
    }

    @Test
    void doesNotThrottleWhenPlayingWithPlayers() {
        boolean shouldThrottle = throttler.shouldThrottle(false, 3);
        assertFalse(shouldThrottle, "Expected no throttling when playing with players");
    }

    @Test
    void customSleepDurationCanBeConfigured() {
        throttler.setThrottleSleepMs(50L);
        assertEquals(50L, throttler.getThrottleSleepMs());
    }
}
