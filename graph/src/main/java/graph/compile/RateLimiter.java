package graph.compile;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class RateLimiter {

    private final double permitsPerSecond;
    private final int burstCapacity;
    private final Clock clock;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public interface Clock {
        long nowMillis();
    }

    private static final class Bucket {
        final AtomicLong lastRefillMillis;
        final AtomicLong tokens;

        private Bucket(long now, double capacity) {
            this.lastRefillMillis = new AtomicLong(now);
            this.tokens = new AtomicLong((long) capacity);
        }
    }

    public RateLimiter(double permitsPerSecond, int burstCapacity, Clock clock) {
        if (permitsPerSecond <= 0) {
            throw new IllegalArgumentException("permitsPerSecond must be positive");
        }
        this.permitsPerSecond = permitsPerSecond;
        this.burstCapacity = Math.max(burstCapacity, 1);
        this.clock = clock;
    }

    public boolean tryAcquire(String key) {
        long now = clock.nowMillis();
        Bucket bucket = buckets.computeIfAbsent(key,
                k -> new Bucket(now, burstCapacity));
        synchronized (bucket) {
            refill(bucket, now);
            if (bucket.tokens.getAndUpdate(current -> current > 0 ? current - 1 : current) > 0) {
                return true;
            }
            return false;
        }
    }

    private void refill(Bucket bucket, long now) {
        long elapsed = now - bucket.lastRefillMillis.get();
        if (elapsed <= 0) {
            return;
        }
        double added = elapsed / 1000.0 * permitsPerSecond;
        long current = bucket.tokens.get();
        long next = Math.min(burstCapacity, current + (long) added);
        if (next > current) {
            bucket.tokens.set(next);
        }
        bucket.lastRefillMillis.set(now);
    }

    public static RateLimiter permissive() {
        return new RateLimiter(Double.MAX_VALUE, Integer.MAX_VALUE,
                System::currentTimeMillis);
    }

    private static final class SystemClockHolder {
        static final Clock INSTANCE = System::currentTimeMillis;
    }

    public static RateLimiter create(double permitsPerSecond, int burstCapacity) {
        return new RateLimiter(permitsPerSecond, burstCapacity, SystemClockHolder.INSTANCE);
    }
}
