package server.service.translation;

import java.time.Instant;

import arc.util.Log;

public class ProviderState {

    private static final long BASE_COOLDOWN_SECONDS = 5;
    private static final long MAX_COOLDOWN_SECONDS = 300; // 5 minutes

    private int consecutiveSuccesses = 0;
    private int consecutiveFailures = 0;
    private volatile Instant cooldownUntil = Instant.MIN;

    public synchronized boolean isAvailable() {
        return Instant.now().isAfter(cooldownUntil);
    }

    public synchronized void recordSuccess(String providerName) {
        if (consecutiveFailures > 0) {
            Log.info("Translation provider '@' started succeeding (recovered after @ consecutive failure(s))",
                    providerName, consecutiveFailures);
            consecutiveFailures = 0;
            cooldownUntil = Instant.MIN;
        } else if (consecutiveSuccesses == 0) {
            Log.info("Translation provider '@' started succeeding", providerName);
        }
        consecutiveSuccesses++;
    }

    public synchronized void recordFailure(String providerName, Throwable error) {
        if (consecutiveSuccesses > 0) {
            Log.warn("Translation provider '@' started failing after @ consecutive success(es): @",
                    providerName, consecutiveSuccesses, error != null ? error.getMessage() : "empty/null result");
            consecutiveSuccesses = 0;
        } else if (consecutiveFailures == 0) {
            Log.warn("Translation provider '@' started failing: @",
                    providerName, error != null ? error.getMessage() : "empty/null result");
        }
        consecutiveFailures++;

        // Exponential backoff: 5s, 10s, 20s, 40s, 80s, 160s, 300s (capped)
        long multiplier = 1L << Math.min(consecutiveFailures - 1, 6);
        long seconds = Math.min(MAX_COOLDOWN_SECONDS, BASE_COOLDOWN_SECONDS * multiplier);
        this.cooldownUntil = Instant.now().plusSeconds(seconds);
        Log.warn("Translation provider '@' placed in cooldown for @s (consecutive failures: @) until @",
                providerName, seconds, consecutiveFailures, cooldownUntil);
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
