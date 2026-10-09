package server.service.translation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ProviderStateTest {

    @Test
    public void testP95LatencyWithNoSamples() {
        ProviderState state = new ProviderState();
        assertEquals(-1, state.getP95LatencyMillis());
    }

    @Test
    public void testP95LatencyWithSingleSample() {
        ProviderState state = new ProviderState();
        state.recordSuccess("test", 50);
        assertEquals(50, state.getP95LatencyMillis());
    }

    @Test
    public void testP95LatencyCalculation() {
        ProviderState state = new ProviderState();
        // Record 100 samples with durations 1 to 100
        for (int i = 1; i <= 100; i++) {
            state.recordSuccess("test", i);
        }
        // index = ceil(0.95 * 99) = ceil(94.05) = 95
        // Sorted array: [1, 2, ..., 100], index 95 has value 96
        assertEquals(96, state.getP95LatencyMillis());
    }

    @Test
    public void testP95LatencyRingBufferWraparound() {
        ProviderState state = new ProviderState();
        // Add 100 samples with value 10
        for (int i = 0; i < 100; i++) {
            state.recordSuccess("test", 10);
        }
        assertEquals(10, state.getP95LatencyMillis());

        // Overwrite all 100 samples with value 200
        for (int i = 0; i < 100; i++) {
            state.recordSuccess("test", 200);
        }
        assertEquals(200, state.getP95LatencyMillis());
    }

    @Test
    public void testSuccessRateColdStart() {
        ProviderState state = new ProviderState();
        assertEquals(0, state.getTotalRequests());
        assertEquals(0, state.getTotalSuccesses());
        assertEquals(0, state.getTotalFailures());
        assertEquals(100.0, state.getRecentSuccessRate());
        assertEquals(100.0, state.getLifetimeSuccessRate());
    }

    @Test
    public void testSuccessRateTracking() {
        ProviderState state = new ProviderState();
        state.recordSuccess("test", 10);
        state.recordSuccess("test", 20);
        state.recordFailure("test", new RuntimeException("err"));

        assertEquals(3, state.getTotalRequests());
        assertEquals(2, state.getTotalSuccesses());
        assertEquals(1, state.getTotalFailures());
        assertEquals((2.0 / 3.0) * 100.0, state.getLifetimeSuccessRate(), 0.001);
        assertEquals((2.0 / 3.0) * 100.0, state.getRecentSuccessRate(), 0.001);
    }

    @Test
    public void testRecentSuccessRateSlidingWindow() {
        ProviderState state = new ProviderState();
        // 100 failures
        for (int i = 0; i < 100; i++) {
            state.recordFailure("test", null);
        }
        assertEquals(0.0, state.getRecentSuccessRate(), 0.001);
        assertEquals(0.0, state.getLifetimeSuccessRate(), 0.001);
        assertEquals(100, state.getTotalRequests());

        // Now record 100 successes: recent should be 100%, lifetime should be 50%
        for (int i = 0; i < 100; i++) {
            state.recordSuccess("test", 15);
        }
        assertEquals(100.0, state.getRecentSuccessRate(), 0.001);
        assertEquals(50.0, state.getLifetimeSuccessRate(), 0.001);
        assertEquals(200, state.getTotalRequests());
        assertEquals(100, state.getTotalSuccesses());
        assertEquals(100, state.getTotalFailures());
    }
}
