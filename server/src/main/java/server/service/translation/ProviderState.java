package server.service.translation;

import java.time.Instant;
import java.util.Arrays;

import arc.util.Log;

import common.translation.TranslationResponse;

public class ProviderState {

    @FunctionalInterface
    public interface ProviderAction<T> {
        T execute() throws Exception;
    }

    private static final int BUFFER_SIZE = 100;
    private static final long BASE_COOLDOWN_SECONDS = 20;
    private static final long MAX_COOLDOWN_SECONDS = 900; // 15 minutes

    private int consecutiveSuccesses = 0;
    private int consecutiveFailures = 0;
    private volatile Instant cooldownUntil = Instant.MIN;

    private final long[] latencyBuffer = new long[BUFFER_SIZE];
    private int latencyCount = 0;
    private int latencyIndex = 0;

    private final boolean[] outcomeBuffer = new boolean[BUFFER_SIZE];
    private int outcomeCount = 0;
    private int outcomeIndex = 0;

    private long totalSuccesses = 0;
    private long totalFailures = 0;

    public synchronized boolean isAvailable() {
        return Instant.now().isAfter(cooldownUntil);
    }

    public TranslationResponse execute(String providerName, ProviderAction<TranslationResponse> action) throws Exception {
        long startTime = System.currentTimeMillis();
        try {
            TranslationResponse response = action.execute();
            long durationMillis = System.currentTimeMillis() - startTime;
            if (response != null && response.getTranslatedText() != null && !response.getTranslatedText().isBlank()) {
                recordSuccess(providerName, durationMillis);
                return response;
            }
            recordFailure(providerName, null);
            return null;
        } catch (Exception e) {
            recordFailure(providerName, e);
            throw e;
        }
    }

    public synchronized void recordSuccess(String providerName) {
        recordSuccess(providerName, -1);
    }

    public synchronized void recordSuccess(String providerName, long durationMillis) {
        totalSuccesses++;
        outcomeBuffer[outcomeIndex] = true;
        outcomeIndex = (outcomeIndex + 1) % BUFFER_SIZE;
        if (outcomeCount < BUFFER_SIZE) {
            outcomeCount++;
        }

        if (durationMillis >= 0) {
            latencyBuffer[latencyIndex] = durationMillis;
            latencyIndex = (latencyIndex + 1) % BUFFER_SIZE;
            if (latencyCount < BUFFER_SIZE) {
                latencyCount++;
            }
        }

        if (consecutiveFailures > 0) {
            Log.info("Translation provider '@' started succeeding (recovered after @ consecutive failure(s)) [recent success: @%, fail: @%, p95 latency: @]",
                    providerName, consecutiveFailures,
                    String.format("%.1f", getRecentSuccessRate()),
                    String.format("%.1f", 100.0 - getRecentSuccessRate()),
                    formatLatency(getP95LatencyMillis()));
            consecutiveFailures = 0;
            cooldownUntil = Instant.MIN;
        } else if (consecutiveSuccesses == 0) {
            Log.info("Translation provider '@' started succeeding [recent success: @%, fail: @%, p95 latency: @]",
                    providerName,
                    String.format("%.1f", getRecentSuccessRate()),
                    String.format("%.1f", 100.0 - getRecentSuccessRate()),
                    formatLatency(getP95LatencyMillis()));
        }
        consecutiveSuccesses++;
    }

    public synchronized void recordFailure(String providerName, Throwable error) {
        totalFailures++;
        outcomeBuffer[outcomeIndex] = false;
        outcomeIndex = (outcomeIndex + 1) % BUFFER_SIZE;
        if (outcomeCount < BUFFER_SIZE) {
            outcomeCount++;
        }

        if (consecutiveSuccesses > 0) {
            Log.info("Translation provider '@' started failing after @ consecutive success(es): @ [recent success: @%, fail: @%, p95 latency: @]",
                    providerName, consecutiveSuccesses, error != null ? error.getMessage() : "empty/null result",
                    String.format("%.1f", getRecentSuccessRate()),
                    String.format("%.1f", 100.0 - getRecentSuccessRate()),
                    formatLatency(getP95LatencyMillis()));
            consecutiveSuccesses = 0;
        } else if (consecutiveFailures == 0) {
            Log.info("Translation provider '@' started failing: @ [recent success: @%, fail: @%, p95 latency: @]",
                    providerName, error != null ? error.getMessage() : "empty/null result",
                    String.format("%.1f", getRecentSuccessRate()),
                    String.format("%.1f", 100.0 - getRecentSuccessRate()),
                    formatLatency(getP95LatencyMillis()));
        }
        consecutiveFailures++;

        // Exponential backoff: 5s, 10s, 20s, 40s, 80s, 160s, 320s 600s (capped)
        long multiplier = 1L << Math.min(consecutiveFailures - 1, 6);
        long seconds = Math.min(MAX_COOLDOWN_SECONDS, BASE_COOLDOWN_SECONDS * multiplier);
        this.cooldownUntil = Instant.now().plusSeconds(seconds);
        Log.info("Translation provider '@' placed in cooldown for @s (consecutive failures: @) until @",
                providerName, seconds, consecutiveFailures, cooldownUntil);
    }

    public static String formatLatency(long p95) {
        return p95 < 0 ? "n/a" : p95 + "ms";
    }

    public synchronized long getP95LatencyMillis() {
        if (latencyCount == 0) {
            return -1;
        }
        long[] copy = Arrays.copyOf(latencyBuffer, latencyCount);
        Arrays.sort(copy);
        int index = (int) Math.ceil(0.95 * (copy.length - 1));
        return copy[index];
    }

    public synchronized double getRecentSuccessRate() {
        if (outcomeCount == 0) {
            return 100.0;
        }
        int successes = 0;
        for (int i = 0; i < outcomeCount; i++) {
            if (outcomeBuffer[i]) {
                successes++;
            }
        }
        return ((double) successes / outcomeCount) * 100.0;
    }

    public synchronized double getLifetimeSuccessRate() {
        long total = totalSuccesses + totalFailures;
        if (total == 0) {
            return 100.0;
        }
        return ((double) totalSuccesses / total) * 100.0;
    }

    public synchronized long getTotalRequests() {
        return totalSuccesses + totalFailures;
    }

    public synchronized long getTotalSuccesses() {
        return totalSuccesses;
    }

    public synchronized long getTotalFailures() {
        return totalFailures;
    }

    public synchronized int getConsecutiveSuccesses() {
        return consecutiveSuccesses;
    }

    public synchronized int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    public Instant getCooldownUntil() {
        return cooldownUntil;
    }
}
