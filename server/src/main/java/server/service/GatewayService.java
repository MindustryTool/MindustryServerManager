package server.service;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import org.apache.hc.core5.net.URIBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.core.type.TypeReference;

import arc.files.Fi;
import arc.util.Log;
import common.ratelimit.KeyedRateLimiter;
import server.utils.HttpClients;
import lombok.Getter;
import lombok.experimental.Accessors;
import common.player.Login;
import common.player.LoginRequest;
import common.player.PlayerRecordPage;
import common.player.RecentPlayer;
import common.server.ServerCommand;
import common.server.ServerSnapshot;
import common.server.StartServer;
import common.translation.TranslationRequest;
import gateway.rpc.RequestContext;
import gateway.wire.StreamReply;
import gateway.rpc.RpcChannel;
import gateway.session.WsSession;
import gateway.wire.WsProtocol;
import common.network.NodeRemoveReason;
import common.event.BaseEvent;
import common.event.ServerEvents;
import common.event.ServerEvents.StartEvent;
import common.event.ServerEvents.StopEvent;
import io.javalin.websocket.WsCloseContext;
import io.javalin.websocket.WsConnectContext;
import io.javalin.websocket.WsContext;
import io.javalin.websocket.WsBinaryMessageContext;
import io.javalin.websocket.WsMessageContext;
import server.EnvConfig;
import server.config.Const;
import server.manager.NodeManager;
import server.service.translation.TranslationService;
import server.types.data.ServerState;
import server.utils.ApiError;
import server.utils.Utils;

public class GatewayService {
    private static final double TRANSLATION_RATE_LIMIT_BURST = 50;
    private static final double TRANSLATION_RATE_LIMIT_REFILL_PER_SECOND = 10;
    private static final Duration TRANSLATION_RATE_LIMIT_IDLE_TTL = Duration.ofMinutes(5);

    private final EventBus eventBus;
    private final EnvConfig envConfig;
    private final NodeManager nodeManager;
    private final TranslationService translationService;
    private final PluginBundleService pluginBundleService;
    private final ConcurrentHashMap<UUID, GatewayClient> clients = new ConcurrentHashMap<>();
    private final KeyedRateLimiter<UUID> translationRateLimiter = new KeyedRateLimiter<>(TRANSLATION_RATE_LIMIT_BURST,
            TRANSLATION_RATE_LIMIT_REFILL_PER_SECOND, TRANSLATION_RATE_LIMIT_IDLE_TTL);

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public GatewayService(EventBus eventBus, EnvConfig envConfig, NodeManager nodeManager) {
        this(eventBus, envConfig, nodeManager, new TranslationService(), PluginBundleService.loadFromImage());
    }

    public GatewayService(EventBus eventBus, EnvConfig envConfig, NodeManager nodeManager,
            TranslationService translationService) {
        this(eventBus, envConfig, nodeManager, translationService, PluginBundleService.loadFromImage());
    }

    public GatewayService(EventBus eventBus, EnvConfig envConfig, NodeManager nodeManager,
            TranslationService translationService, PluginBundleService pluginBundleService) {
        this.eventBus = eventBus;
        this.envConfig = envConfig;
        this.nodeManager = nodeManager;
        this.translationService = translationService;
        this.pluginBundleService = pluginBundleService;

        nodeManager.onKilled(serverId -> {
            eventBus.emit(new StopEvent(serverId, NodeRemoveReason.PROCESS_KILLED));
        });

        scheduler.scheduleWithFixedDelay(() -> {
            try {
                sweep();
            } catch (Exception e) {
                Log.err("Error sweeping gateway clients", e);
            }
        }, 15, 15, TimeUnit.SECONDS);
    }

    void sweep() {
        Set<UUID> runningIds = new HashSet<>();

        for (ServerState state : nodeManager.list()) {
            if (state.running() && state.meta().isPresent()) {
                runningIds.add(state.meta().get().getConfig().getId());
            }
        }

        clients.forEach((serverId, client) -> {
            if (!client.isSocketClosed()) {
                return;
            }

            if (!runningIds.contains(serverId)) {
                clients.remove(serverId);
            }
        });
    }

    public GatewayClient of(UUID serverId) {
        return clients.computeIfAbsent(serverId, _ignore -> new GatewayClient(serverId));
    }

    public boolean isHosting(UUID serverId) {
        try {
            return clients.containsKey(serverId)
                    && nodeManager.isRunning(serverId)
                    && of(serverId).server().isHosting().get(5, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return false;
        } catch (InterruptedException | ExecutionException e) {
            throw ApiError.internal(e);
        }
    }

    public boolean terminate(UUID serverId, NodeRemoveReason reason) {
        var client = clients.get(serverId);

        if (client == null) {
            return nodeManager.remove(serverId, reason);
        }

        client.terminate(reason);

        return true;
    }

    public TranslationService getTranslationService() {
        return translationService;
    }

    @Accessors(fluent = true)
    public class GatewayClient {
        @Getter
        private final UUID id;

        private final RpcChannel rpcChannel = RpcChannel.withExecutor(Const.executorService);

        @Getter
        private final Backend backend = new Backend();
        @Getter
        private final Server server = new Server();
        public final Instant createdAt = Instant.now();

        public GatewayClient(UUID id) {
            this.id = id;

            this.registerHandler("get-total-player", Void.class, _ctx -> 0L);
            this.registerHandler("login", LoginRequest.class, ctx -> backend.login(id, ctx.body()));
            this.registerHandler("host", UUID.class, ctx -> backend.host(ctx.body()));
            this.registerHandler("translate", TranslationRequest.class, ctx -> {
                if (!translationRateLimiter.tryAcquire(id)) {
                    Log.debug("Translation rate limited for server @", id);
                    return null;
                }

                var req = ctx.body();
                try {
                    return translationService.translate(req.getText(), req.getTargetLang());
                } catch (Exception e) {
                    Log.warn("Translation error: @", e.getMessage());
                    return null;
                }
            });

            this.registerHandler("get-plugin-version", Void.class, _ctx -> {
                return pluginBundleService.getPluginVersion();
            });

            this.registerHandler("download-plugin", Void.class, _ctx -> {
                return new StreamReply(pluginBundleService.downloadPlugin());
            });

            this.registerHandler("event", JsonNode.class, ctx -> {
                var event = ctx.body();
                var name = event.get("name").asText(null);

                if (name == null) {
                    Log.warn("Invalid event: " + event.asText());
                    return null;
                }

                var eventType = ServerEvents.getEventMap().get(name);
                if (eventType == null) {
                    Log.warn("Invalid event name: " + name + " in " + ServerEvents.getEventMap().keySet());
                    return null;
                }

                BaseEvent data = (BaseEvent) Utils.readJsonAsClass(event, eventType);
                eventBus.emit(data);

                return null;
            });
        }

        public synchronized void onOpen(WsConnectContext context) {
            Log.info("Gateway client connected: " + id);

            // Overwrite wins: a duplicate or reconnect open replaces the socket.
            eventBus.emit(new StartEvent(id));
            rpcChannel.onOpen(new JavalinSession(context));
        }

        public synchronized void onClose(WsCloseContext context) {
            Log.info("Gateway client disconnected: " + id);

            if (context == null) {
                // TODO: Proper exception + print message only
                rpcChannel.onClose(new RuntimeException("Gateway client disconnected: " + id));
            } else {
                boolean cleared = rpcChannel.onClose(new JavalinSession(context),
                        new RuntimeException("Gateway client disconnected: " + id));
                if (!cleared) {
                    Log.info("Ignoring stale close for replaced session: " + id);
                    return;
                }
            }

            eventBus.emit(new StopEvent(id, NodeRemoveReason.SOCKET_DISCONNECT));
        }

        private boolean isSocketClosed() {
            WsSession session = rpcChannel.current();
            return session == null || !session.isOpen();
        }

        public boolean terminate(NodeRemoveReason reason) {
            if (rpcChannel.isConnected()) {
                try {
                    this.server.shutdown().get(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    Log.err("Shutdown request failed for client " + id + ", continuing termination", e);
                }

                WsSession session = rpcChannel.current();
                if (session != null) {
                    try {
                        session.close(WsProtocol.REPLACED_CLOSE_CODE, "Terminate by server");
                    } catch (Exception e) {
                        Log.err("Error closing session for client " + id, e);
                    }
                }
            }

            try {
                nodeManager.remove(id, reason);
            } catch (Exception e) {
                Log.err("Error removing node: " + id, e);
            }

            eventBus.emit(new StopEvent(id, reason));
            Log.info("[red]Client terminated: " + id + " reason: " + reason);

            return true;
        }

        public void onMessage(WsMessageContext context) {
            rpcChannel.onTextMessage(new JavalinSession(context), context.message());
        }

        public void onBinary(WsBinaryMessageContext context) {
            rpcChannel.onBinaryMessage(ByteBuffer.wrap(context.data()));
        }

        public <Req, Res> void registerHandler(String type, Class<Req> clazz,
                Function<RequestContext<Req>, Res> handler) {
            rpcChannel.registerHandler(type, clazz, handler);
        }

        /** Exposed for tests and adapters. */
        public RpcChannel rpcChannel() {
            return rpcChannel;
        }

        /** Wait up to the timeout for the plugin connection to open. */
        public CompletableFuture<WsSession> awaitSession(Duration timeout) {
            return rpcChannel.awaitSession(timeout);
        }

        public class Backend {
            private final HttpClient httpClient = HttpClients.shared();

            private HttpRequest.Builder createRequest(Object... segments) {
                try {
                    String[] str = new String[segments.length];

                    for (int i = 0; i < segments.length; i++) {
                        str[i] = segments[i].toString();
                    }

                    String base = Const.API_URL;
                    while (base.endsWith("/")) {
                        base = base.substring(0, base.length() - 1);
                    }

                    return HttpRequest.newBuilder()
                            .uri(new URIBuilder(base + "/" + String.join("/", str)).build())
                            .header("X-SERVER-ID", id.toString())
                            .header("X-MANAGER-AUTH", envConfig.serverConfig().accessToken())
                            .header("Connection", "close")
                            .timeout(Duration.ofMinutes(2));

                } catch (Exception e) {
                    throw new ApiError(500, "Internal server error", e);
                }
            }

            public Login login(UUID id, LoginRequest body) {
                try {
                    HttpRequest request = createRequest("servers", id, "login")
                            .POST(HttpRequest.BodyPublishers.ofString(Utils.toJsonString(body)))
                            .header("Content-Type", "application/json")
                            .timeout(Duration.ofSeconds(5))
                            .build();

                    HttpResponse<String> result = httpClient.send(request, BodyHandlers.ofString());

                    if (result.statusCode() >= 400) {
                        throw new ApiError(result.statusCode(), "Failed to login server: " + result.body());
                    }

                    return Utils.readJsonAsClass(result.body(), Login.class);
                } catch (Exception e) {
                    if (e instanceof ApiError apiError) {
                        throw apiError;
                    }
                    throw new ApiError(500, "Internal server error", e);
                }
            }

            public String host(UUID id) {
                try {
                    HttpRequest request = createRequest("servers", id, "host-server")
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .header("Content-Type", "application/json")
                            .timeout(Duration.ofMinutes(2))
                            .build();

                    HttpResponse<String> result = httpClient.send(request, BodyHandlers.ofString());

                    if (result.statusCode() >= 400) {
                        throw new ApiError(result.statusCode(), "Failed to host server: " + result.body());
                    }

                    return result.body();
                } catch (Exception e) {
                    if (e instanceof ApiError apiError) {
                        throw apiError;
                    }
                    throw new ApiError(500, "Internal server error", e);
                }
            }
        }

        public class Server {
            private <R> CompletableFuture<R> sendRequest(String type, Object payload, Class<R> clazz) {
                return rpcChannel.sendRequest(type, payload, clazz, Duration.ofMinutes(1));
            }

            private CompletableFuture<Void> sendRequest(String type, Object payload) {
                return rpcChannel.sendRequest(type, payload, Void.class, Duration.ofMinutes(1));
            }

            public CompletableFuture<JsonNode> getJson() {
                return sendRequest("get-json", null, JsonNode.class);
            }

            public CompletableFuture<Void> updatePlayer(String uuid, Login request) {
                return sendRequest("update-player", request);
            }

            public CompletableFuture<Boolean> pause() {
                return sendRequest("pause", null, Boolean.class);
            }

            public CompletableFuture<ServerSnapshot> getState() {
                return sendRequest("get-state", null, ServerSnapshot.class);
            }

            public CompletableFuture<byte[]> getImage() {
                return sendRequest("generate-map-image", null)
                        .thenApply(res -> {
                            Fi file = nodeManager.getFile(id, "map-preview-image.png");

                            if (!file.exists()) {
                                return new byte[1];
                            }

                            return file.readBytes();
                        });
            }

            public CompletableFuture<Void> sendCommand(String... command) {
                return sendRequest("send-command", command);
            }

            public CompletableFuture<Void> say(String message) {
                return sendRequest("say", message);
            }

            public CompletableFuture<Void> host(StartServer request) {
                return sendRequest("host", request);
            }

            public CompletableFuture<Void> sendChat(JsonNode request) {
                return sendRequest("chat", request);
            }

            public CompletableFuture<Void> shutdown() {
                return sendRequest("shutdown", null);
            }

            public CompletableFuture<Boolean> isHosting() {
                return sendRequest("is-hosting", null, Boolean.class);
            }

            public CompletableFuture<List<ServerCommand>> getCommands() {
                return sendRequest("get-commands", null, JsonNode.class).thenApply(n -> {
                    try {
                        return Utils.getObjectMapper().readerForListOf(ServerCommand.class).readValue(n);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
            }

            public CompletableFuture<PlayerRecordPage> getPlayersInfo(int page, int size,
                    Boolean banned, String filter//
            ) {
                ObjectNode payload = Utils.getObjectMapper().createObjectNode();
                payload.put("page", page);
                payload.put("size", size);
                if (banned == null) {
                    payload.putNull("banned");
                } else {
                    payload.put("banned", banned);
                }
                if (filter == null) {
                    payload.putNull("filter");
                } else {
                    payload.put("filter", filter);
                }

                return sendRequest("get-players-info", payload, PlayerRecordPage.class);
            }

            public CompletableFuture<Map<String, Long>> getKickedIps() {
                return sendRequest("get-kicked-ips", null, JsonNode.class)
                        .thenApply(n -> {
                            if (n == null || n.isNull()) {
                                return Collections.emptyMap();
                            }
                            return Utils.getObjectMapper().convertValue(n, new TypeReference<Map<String, Long>>() {
                            });
                        });
            }

            public CompletableFuture<List<RecentPlayer>> getRecentPlayers() {
                return sendRequest("get-recent-players", null, JsonNode.class).thenApply(n -> {
                    try {
                        return Utils.getObjectMapper().readerForListOf(RecentPlayer.class).readValue(n);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
            }

            public CompletableFuture<Boolean> deleteKickedIp(String ip) {
                return sendRequest("delete-kicked-ip", ip, Boolean.class);
            }
        }

        /** Session bound to a single connection; a fresh one is created per open. */
        private class JavalinSession implements WsSession {
            private final WsContext socket;
            private final String connectionId;

            JavalinSession(WsContext socket) {
                this.socket = socket;
                String id = null;
                try {
                    id = socket == null ? null : socket.sessionId();
                } catch (Exception ignored) {
                }
                this.connectionId = id;
            }

            @Override
            public void sendText(String text) {
                if (socket == null) {
                    throw new IllegalStateException("No open gateway session for " + id);
                }
                socket.send(text);
            }

            @Override
            public void sendBinary(ByteBuffer data) {
                if (socket == null) {
                    throw new IllegalStateException("No open gateway session for " + id);
                }
                socket.send(data.duplicate());
            }

            @Override
            public void close(int code, String reason) {
                if (socket != null) {
                    socket.closeSession(code, reason);
                }
            }

            @Override
            public boolean isOpen() {
                return socket != null && socket.session.isOpen();
            }

            @Override
            public boolean equals(Object other) {
                if (this == other) {
                    return true;
                }
                if (!(other instanceof JavalinSession that)) {
                    return false;
                }
                if (connectionId != null && that.connectionId != null) {
                    return connectionId.equals(that.connectionId);
                }
                return false;
            }

            @Override
            public int hashCode() {
                return connectionId != null ? connectionId.hashCode() : System.identityHashCode(this);
            }
        }

    }
}
