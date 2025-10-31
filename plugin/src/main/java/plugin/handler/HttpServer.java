package plugin.handler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.eclipse.jetty.util.thread.ExecutorThreadPool;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import arc.util.Log;
import plugin.ServerController;
import plugin.controller.GeneralController;
import plugin.controller.WorkflowController;
import dto.ServerStateDto;
import events.ServerEvents.ServerStateEvent;
import plugin.utils.Utils;
import io.javalin.Javalin;
import io.javalin.json.JavalinJackson;
import io.javalin.plugin.bundled.RouteOverviewPlugin;
import io.javalin.http.sse.SseClient;

public class HttpServer {
    private static final List<Object> buffer = new ArrayList<>();
    private static Javalin app;
    private static SseClient eventListener = null;

    public static void fire(Object event) {
        if (eventListener == null) {
            buffer.add(event);
            if (buffer.size() > 1000) {
                buffer.remove(0);
            }
        } else {
            eventListener.sendEvent(event);
        }
    }

    public static void init() {
        if (app != null) {
            app.stop();
        }

        ServerController.BACKGROUND_SCHEDULER.scheduleWithFixedDelay(() -> sendStateUpdate(), 0, 60, TimeUnit.SECONDS);

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

            config.http.asyncTimeout = 5_000;
            config.useVirtualThreads = true;

            ExecutorThreadPool pool = new ExecutorThreadPool(50, 0);
            pool.setName("HttpServer");
            config.jetty.threadPool = pool;
            config.jetty.modifyServer(server -> server.setStopTimeout(5_000)); // wait 5 seconds for existing requests

            config.registerPlugin(new RouteOverviewPlugin());

            config.requestLogger.http((ctx, ms) -> {
                if (!ctx.fullUrl().contains("state") && !ctx.fullUrl().contains("hosting")) {
                    Log.debug("[" + ctx.method().name() + "] " + Math.round(ms) + "ms " + ctx.fullUrl());
                }
            });
        });

        app.beforeMatched((ctx) -> {
            if (ServerController.isUnloaded) {
                throw new ServerUnloadedException();
            }
        });

        Log.info("Setup http server");

        GeneralController.init(app);
        WorkflowController.init(app);

        app.sse("events", HttpServer::onClientConnect);

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
                Log.err("Unhandled api exception", exception);
                HashMap<String, Object> result = new HashMap<>();
                result.put("message", exception.getMessage() == null ? "Unknown error" : exception.getMessage());
                ctx.status(500).json(result);
            } catch (Exception e) {
                Log.err("Failed to create error response", e);
                ctx.status(500).json("Failed to create error response");
            }
        });

        if (!ServerController.isUnloaded) {
            app.start(9999);
            Log.info("Http server started on port 9999");
        }

        Log.info("Setup http server done");
    }

    private static synchronized void onClientConnect(SseClient client) {
        String createdAt = client.ctx().header("X-CREATED-AT");

        client.onClose(() -> {
            Log.info("Client disconnected with createdAt: " + createdAt);
            eventListener = null;
        });

        Log.info("Client connected with createdAt: " + createdAt);

        client.keepAlive();

        List<Object> copy = new ArrayList<>(buffer);

        buffer.clear();

        for (Object event : copy) {
            client.sendEvent(event);
        }

        if (eventListener != null) {
            Log.info("Closing existing event listener with createdAt: " + eventListener.ctx().header("X-CREATED-AT")
                    + ", terminated: " + eventListener.terminated());
            if (!eventListener.terminated()) {
                eventListener.close();
            }
            eventListener = null;
        }

        eventListener = client;

        sendStateUpdate();
    }

    public static void sendStateUpdate() {
        try {
            ServerStateDto state = Utils.getState();
            ServerStateEvent event = new ServerStateEvent(ServerController.SERVER_ID, Arrays.asList(state));

            fire(event);
        } catch (Exception error) {
            Log.err(error);
        }
    }

    public static void unload() {
        if (eventListener != null) {
            eventListener.close();
        }

        buffer.clear();

        Log.info("Event listener closed");

        app.stop();
        app = null;

        Log.info("Stop http server");
    }

    public static class RequestInfo {
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

    public static class ServerUnloadedException extends IllegalStateException {
        public ServerUnloadedException() {
            super("Server is unloaded");
        }
    }
}
