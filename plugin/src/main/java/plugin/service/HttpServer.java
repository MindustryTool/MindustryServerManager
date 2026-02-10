package plugin.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import arc.util.Log;
import plugin.PluginState;
import plugin.annotations.Component;
import plugin.annotations.Destroy;
import plugin.annotations.Init;
import plugin.annotations.Listener;
import plugin.annotations.Schedule;
import plugin.Control;
import plugin.controller.GeneralController;
import plugin.event.SessionCreatedEvent;
import plugin.event.SessionRemovedEvent;
import dto.ServerStateDto;
import events.ServerEvents.ServerStateEvent;
import plugin.utils.Utils;
import io.javalin.Javalin;
import io.javalin.json.JavalinJackson;
import io.javalin.plugin.bundled.RouteOverviewPlugin;
import lombok.RequiredArgsConstructor;
import mindustry.game.EventType.StateChangeEvent;
import io.javalin.http.sse.SseClient;

@Component
@RequiredArgsConstructor
public class HttpServer {
    private final List<Object> buffer = new ArrayList<>();
    private Javalin app;
    private SseClient eventListener = null;
    private Instant lastSendEvent = Instant.now();

    private final Duration HEARTBEAT_DURATION = Duration.ofSeconds(10);

    private final ApiGateway apiGateway;

    @Init
    public void init() {
        if (app != null) {
            app.stop();
        }

        app = Javalin.create(config -> {
            config.showJavalinBanner = false;
            config.jsonMapper(new JavalinJackson().updateMapper(mapper -> {
                mapper//

                        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)//
                        .configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true)//
                        .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false)
                        .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, true);

                mapper.registerModule(new JavaTimeModule());
            }));

            config.registerPlugin(new RouteOverviewPlugin());

            config.requestLogger.http((ctx, ms) -> {
                if (!ctx.fullUrl().contains("state") && !ctx.fullUrl().contains("hosting")) {
                    Log.info("[" + ctx.method().name() + "] " + Math.round(ms) + "ms " + ctx.fullUrl());
                }
            });
        });

        app.beforeMatched((ctx) -> {
            if (Control.state != PluginState.LOADED) {
                Log.info("Server unloaded");
                throw new ServerUnloadedException();
            }

            Log.info("[" + ctx.method().name() + "] " + ctx.fullUrl());
        });

        GeneralController.init(app);

        app.sse("events", this::onClientConnect);

        app.exception(TimeoutException.class, (exception, ctx) -> {
            Log.warn("Timeout exception", exception);
            HashMap<String, Object> result = new HashMap<>();
            result.put("message", exception.getMessage() == null ? "Unknown error" : exception.getMessage());
            ctx.status(400).json(result);
        });

        app.exception(ServerUnloadedException.class, (exception, ctx) -> {
            HashMap<String, Object> result = new HashMap<>();
            result.put("message", "Server is unloaded");
            ctx.status(400).json(result);
        });

        app.exception(Exception.class, (exception, ctx) -> {
            try {
                Log.err("Unhandled api exception at " + ctx.method() + " " + ctx.url(), exception);
                HashMap<String, Object> result = new HashMap<>();
                result.put("message", exception.getMessage() == null ? "Unknown error" : exception.getMessage());
                ctx.status(500).json(result);
            } catch (Exception e) {
                Log.err("Failed to create error response", e);
                ctx.status(500).json("Failed to create error response");
            }
        });

        app.start(9999);
        Log.info("Http server started on port 9999");

        apiGateway.requestConnection();
    }

    @Schedule(delay = 5, fixedDelay = 1, unit = TimeUnit.SECONDS)
    private void keepAlive() {
        if (!isConnected()) {
            apiGateway.requestConnection();
        } else if (Duration.between(lastSendEvent, Instant.now()).compareTo(HEARTBEAT_DURATION) > 0) {
            sendStateUpdate();
        }
    }

    public void fire(Object event) {
        if (eventListener == null) {
            buffer.add(event);
            if (buffer.size() > 1000) {
                buffer.remove(0);
            }
        } else {
            eventListener.sendEvent(event);
            lastSendEvent = Instant.now();
        }
    }

    private synchronized void onClientConnect(SseClient client) {
        if (eventListener != null && !eventListener.terminated()) {
            Log.info("Client already connected");
            client.close();
        }

        client.onClose(() -> {
            Log.info("Client disconnected");
            eventListener = null;
        });

        Log.info("Client connected");

        client.keepAlive();

        List<Object> copy = new ArrayList<>(buffer);

        buffer.clear();

        for (Object event : copy) {
            client.sendEvent(event);
        }

        if (eventListener != null) {
            Log.info("Closing existing event listener, terminated: " + eventListener.terminated());

            if (!eventListener.terminated()) {
                eventListener.close();
            }

            eventListener = null;
        }

        sendStateUpdate();

        eventListener = client;
    }

    @Listener(SessionCreatedEvent.class)
    private void onSessionCreated() {
        sendStateUpdate();
    }

    @Listener(SessionRemovedEvent.class)
    private void onSessionRemoved() {
        sendStateUpdate();
    }

    @Listener(StateChangeEvent.class)
    private void onStateChange() {
        sendStateUpdate();
    }

    private void sendStateUpdate() {
        try {
            ServerStateDto state = Utils.getState();
            ServerStateEvent event = new ServerStateEvent(Control.SERVER_ID, Arrays.asList(state));

            fire(event);
        } catch (Exception error) {
            Log.err("Failed to send state update", error);
        }
    }

    public boolean isConnected() {
        return eventListener != null && !eventListener.terminated();
    }

    @Destroy
    public void destroy() {
        if (eventListener != null) {
            eventListener.close();
        }

        buffer.clear();

        try {
            if (app != null) {
                app.stop();
                app = null;
            }
        } catch (Exception e) {
            Log.err("Failed to stop http server", e);
        }
    }

    public class RequestInfo {
        public final String method;
        public final String path;
        public final String ip;
        public final long timestamp;

        public RequestInfo(String method, String path, String ip, long timestamp) {
            this.method = method;
            this.path = path;
            this.ip = ip;
            this.timestamp = timestamp;
        }
    }

    public class ServerUnloadedException extends IllegalStateException {
        public ServerUnloadedException() {
            super("Server is unloaded");
        }
    }
}
