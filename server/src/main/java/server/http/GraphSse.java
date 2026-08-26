package server.http;

import io.javalin.Javalin;
import io.javalin.http.sse.SseClient;

import java.io.IOException;

/**
 * Javalin wiring for the graph SSE streams:
 * {@code GET /api/v2/graphs/{id}/debug/stream} (per-graph debug telemetry)
 * and {@code GET /api/v2/graphs/events} (global graph lifecycle feed).
 *
 * Heartbeats and disconnect cleanup are owned by the shared
 * {@link GraphSseBroker}; producers publish through
 * {@code GraphSse.broker()}.
 */
public final class GraphSse {

    private static final GraphSseBroker BROKER = new GraphSseBroker();

    private GraphSse() {
    }

    public static GraphSseBroker broker() {
        return BROKER;
    }

    public static void register(Javalin app) {
        app.sse("/api/v2/graphs/{id}/debug/stream", client -> {
            String topic = debugTopic(client.ctx().pathParam("id"));
            Runnable detach = BROKER.attach(topic, adapt(client));
            client.onClose(detach);
        });

        app.sse("/api/v2/graphs/events", client -> {
            Runnable detach = BROKER.attach("events", adapt(client));
            client.onClose(detach);
        });
    }

    public static String debugTopic(String graphId) {
        return "debug:" + graphId;
    }

    private static GraphSseBroker.Sink adapt(SseClient client) {
        return new GraphSseBroker.Sink() {
            @Override
            public void send(String eventName, String data) throws IOException {
                client.sendEvent(eventName, data);
            }

            @Override
            public void heartbeat() throws IOException {
                client.sendComment("hb");
            }
        };
    }
}
