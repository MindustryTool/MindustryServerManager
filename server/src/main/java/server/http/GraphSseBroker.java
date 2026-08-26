package server.http;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Topic-based broker feeding the graph SSE endpoints. Sinks are detached
 * automatically when their close callback fires, and a heartbeat comment is
 * written to every live sink so intermediaries do not time the stream out.
 */
public final class GraphSseBroker implements AutoCloseable {

    /** Minimal sink abstraction so the broker is testable without Javalin. */
    public interface Sink {
        void send(String eventName, String data) throws IOException;

        default void heartbeat() throws IOException {
        }
    }

    private static final long DEFAULT_HEARTBEAT_SECONDS = 15;

    private final Map<String, List<Sink>> sinksByTopic = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "graph-sse-heartbeat");
                t.setDaemon(true);
                return t;
            });

    public GraphSseBroker() {
        this(DEFAULT_HEARTBEAT_SECONDS);
    }

    public GraphSseBroker(long heartbeatSeconds) {
        scheduler.scheduleWithFixedDelay(() -> {
            for (List<Sink> sinks : sinksByTopic.values()) {
                for (Sink sink : sinks) {
                    try {
                        sink.heartbeat();
                    } catch (IOException e) {
                        sinks.remove(sink);
                    }
                }
            }
        }, heartbeatSeconds, heartbeatSeconds, TimeUnit.SECONDS);
    }

    /**
     * Registers {@code sink} under {@code topic}; the returned runnable
     * detaches it (idempotent) and must be invoked on client disconnect.
     */
    public Runnable attach(String topic, Sink sink) {
        List<Sink> sinks = sinksByTopic.computeIfAbsent(topic,
                k -> new CopyOnWriteArrayList<>());
        sinks.add(sink);
        return () -> detach(topic, sink);
    }

    public void detach(String topic, Sink sink) {
        List<Sink> sinks = sinksByTopic.get(topic);
        if (sinks != null) {
            sinks.remove(sink);
            if (sinks.isEmpty()) {
                sinksByTopic.remove(topic, sinks);
            }
        }
    }

    /**
     * Publishes {@code data} as an SSE event named {@code eventName} to every
     * sink of {@code topic}. Sinks that fail to write are detached.
     */
    public void publish(String topic, String eventName, String data) {
        List<Sink> sinks = sinksByTopic.get(topic);
        if (sinks == null) {
            return;
        }
        for (Sink sink : List.copyOf(sinks)) {
            try {
                sink.send(eventName, data);
            } catch (IOException e) {
                detach(topic, sink);
            }
        }
    }

    public int subscriberCount(String topic) {
        List<Sink> sinks = sinksByTopic.get(topic);
        return sinks == null ? 0 : sinks.size();
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        sinksByTopic.clear();
    }
}
