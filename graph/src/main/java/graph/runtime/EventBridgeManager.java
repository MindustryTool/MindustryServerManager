package graph.runtime;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class EventBridgeManager {

    @FunctionalInterface
    public interface SubscriptionFactory {
        AutoCloseable subscribe(String eventId) throws Exception;
    }

    private static final class Entry {
        final AutoCloseable subscription;
        int refCount;

        Entry(AutoCloseable subscription) {
            this.subscription = subscription;
            this.refCount = 1;
        }
    }

    private final SubscriptionFactory factory;
    private final Map<String, Entry> active = new ConcurrentHashMap<>();

    public EventBridgeManager(SubscriptionFactory factory) {
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    public synchronized void acquire(String eventId) {
        Entry entry = active.get(eventId);
        if (entry != null) {
            entry.refCount++;
            return;
        }
        try {
            active.put(eventId, new Entry(factory.subscribe(eventId)));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to subscribe to event '"
                    + eventId + "'", e);
        }
    }

    public synchronized void release(String eventId) {
        Entry entry = active.get(eventId);
        if (entry == null) {
            return;
        }
        entry.refCount--;
        if (entry.refCount <= 0) {
            active.remove(eventId);
            try {
                entry.subscription.close();
            } catch (Exception e) {
                throw new IllegalStateException("Failed to unsubscribe from event '"
                        + eventId + "'", e);
            }
        }
    }

    public synchronized int refCount(String eventId) {
        Entry entry = active.get(eventId);
        return entry == null ? 0 : entry.refCount;
    }

    public synchronized boolean isSubscribed(String eventId) {
        return active.containsKey(eventId);
    }

    public synchronized Set<String> subscribedEvents() {
        return Set.copyOf(active.keySet());
    }

    public synchronized void releaseAll() {
        for (String eventId : List.copyOf(active.keySet())) {
            release(eventId);
        }
    }
}
