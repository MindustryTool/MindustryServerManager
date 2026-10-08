package common.ratelimit;

import java.util.function.LongSupplier;

public class TokenBucket {

    private final double capacity;
    private final double refillPerSecond;
    private final LongSupplier nanoTime;

    private double tokens;
    private long lastRefillNanos;

    public TokenBucket(double capacity, double refillPerSecond) {
        this(capacity, refillPerSecond, System::nanoTime);
    }

    public TokenBucket(double capacity, double refillPerSecond, LongSupplier nanoTime) {
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
        this.nanoTime = nanoTime;
        this.tokens = capacity;
        this.lastRefillNanos = nanoTime.getAsLong();
    }

    public synchronized boolean tryAcquire() {
        refill();

        if (tokens >= 1.0) {
            tokens -= 1.0;
            return true;
        }

        return false;
    }

    public synchronized double availableTokens() {
        refill();
        return tokens;
    }

    private void refill() {
        long now = nanoTime.getAsLong();
        long elapsed = now - lastRefillNanos;

        if (elapsed <= 0) {
            return;
        }

        tokens = Math.min(capacity, tokens + elapsed / 1_000_000_000.0 * refillPerSecond);
        lastRefillNanos = now;
    }
}
