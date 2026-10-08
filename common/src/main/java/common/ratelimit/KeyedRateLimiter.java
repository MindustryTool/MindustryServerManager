package common.ratelimit;

import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

public class KeyedRateLimiter<K> {

    private static final Duration DEFAULT_IDLE_TTL = Duration.ofMinutes(10);

    private final ConcurrentHashMap<K, Entry> buckets = new ConcurrentHashMap<>();
    private final Supplier<TokenBucket> bucketFactory;
    private final LongSupplier nanoTime;
    private final long idleTtlNanos;

    private long lastSweepNanos;

    public KeyedRateLimiter(double capacity, double refillPerSecond) {
        this(capacity, refillPerSecond, DEFAULT_IDLE_TTL);
    }

    public KeyedRateLimiter(double capacity, double refillPerSecond, Duration idleTtl) {
        this(capacity, refillPerSecond, idleTtl, System::nanoTime);
    }

    public KeyedRateLimiter(double capacity, double refillPerSecond, Duration idleTtl, LongSupplier nanoTime) {
        this(() -> new TokenBucket(capacity, refillPerSecond, nanoTime), idleTtl, nanoTime);
    }

    public KeyedRateLimiter(Supplier<TokenBucket> bucketFactory, Duration idleTtl, LongSupplier nanoTime) {
        this.bucketFactory = bucketFactory;
        this.idleTtlNanos = idleTtl.toNanos();
        this.nanoTime = nanoTime;
        this.lastSweepNanos = nanoTime.getAsLong();
    }

    public boolean tryAcquire(K key) {
        sweepIfDue();

        Entry entry = buckets.computeIfAbsent(key, _key -> new Entry(bucketFactory.get()));
        entry.lastAccessNanos = nanoTime.getAsLong();
        return entry.bucket.tryAcquire();
    }

    public void evictIdle(Duration idle) {
        evictIdleBefore(nanoTime.getAsLong() - idle.toNanos());
    }

    public int size() {
        return buckets.size();
    }

    private void sweepIfDue() {
        long now = nanoTime.getAsLong();

        if (now - lastSweepNanos < idleTtlNanos) {
            return;
        }

        sweep(now);
    }

    private synchronized void sweep(long now) {
        if (now - lastSweepNanos < idleTtlNanos) {
            return;
        }

        evictIdleBefore(now - idleTtlNanos);
        lastSweepNanos = now;
    }

    private void evictIdleBefore(long cutoff) {
        Iterator<Map.Entry<K, Entry>> iterator = buckets.entrySet().iterator();

        while (iterator.hasNext()) {
            if (iterator.next().getValue().lastAccessNanos < cutoff) {
                iterator.remove();
            }
        }
    }

    private static final class Entry {

        private final TokenBucket bucket;
        private volatile long lastAccessNanos;

        private Entry(TokenBucket bucket) {
            this.bucket = bucket;
        }
    }
}
