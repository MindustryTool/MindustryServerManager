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
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import org.apache.hc.core5.net.URIBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.core.type.TypeReference;

import arc.files.Fi;
import arc.util.Log;
import server.utils.HttpClients;
import lombok.Getter;
import lombok.experimental.Accessors;
import dto.LoginDto;
import dto.LoginRequestDto;
import dto.PlayerInfoPageDto;
import dto.RecentPlayerDto;
import dto.ServerCommandDto;
import dto.ServerStateDto;
import dto.StartServerDto;
import dto.TranslationRequestDto;
import gateway.rpc.WsRpcChannel;
import gateway.session.WsSession;
import enums.NodeRemoveReason;
import events.BaseEvent;
import events.ServerEvents;
import events.ServerEvents.LogEvent;
import events.ServerEvents.StartEvent;
import events.ServerEvents.StopEvent;
import io.javalin.websocket.WsCloseContext;
import io.javalin.websocket.WsCloseStatus;
import io.javalin.websocket.WsConnectContext;
import io.javalin.websocket.WsContext;
import io.javalin.websocket.WsBinaryMessageContext;
import io.javalin.websocket.WsMessageContext;
import server.EnvConfig;
import server.config.Const;
import server.manager.NodeManager;
import server.service.translation.TranslationService;
import server.utils.ApiError;
import server.utils.Utils;

public class GatewayService {
    private final EventBus eventBus;
    private final EnvConfig envConfig;
    private final NodeManager nodeManager;
    private final TranslationService translationService;
    private final PluginBundleService pluginBundleService;
    private final ConcurrentHashMap<UUID, GatewayClient> clients = new ConcurrentHashMap<>();
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
                clients.values().removeIf(client -> {
                    if (client.shouldTerminate()) {
                        client.terminate(NodeRemoveReason.NOT_CONNECTED);
                        return true;
                    }

                    return false;
                });

                clients.values().forEach(GatewayClient::checkDisconnect);
            } catch (Exception e) {
                Log.err("Error checking disconnect", e);
            }
        }, 15, 15, TimeUnit.SECONDS);
    }

    public GatewayClient of(UUID serverId) {
        return clients.computeIfAbsent(serverId, _ignore -> new GatewayClient(serverId));
    }

    /** First live plugin connection, or null when none are hosted. */
    public GatewayClient anyNode() {
        return clients.values().stream().findFirst().orElse(null);
    }

    public boolean isHosting(UUID serverId) {
        try {
            return clients.containsKey(serverId)
                    && nodeManager.isRunning(serverId)
                    && of(serverId).server().isHosting().get(5, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            Log.err("Timeout when checking if server is hosting after 5 seconds: " + serverId);
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

        boolean handled = client.terminate(reason);

        if (handled) {
            clients.remove(serverId);
        }

        return true;
    }

    public TranslationService getTranslationService() {
        return translationService;
    }

    @Accessors(fluent = true)
    public class GatewayClient {
        private static final Duration DISCONNECT_WARN_AFTER = Duration.ofSeconds(60);
        private static final Duration TERMINATE_CONNECTION_AFTER = Duration.ofMinutes(3);

        @Getter
        private final UUID id;

        private volatile Instant lastDisconnectAt;

        private final WsRpcChannel rpcChannel = WsRpcChannel.withExecutor(Const.executorService);
        private volatile boolean removed = false;

        @Getter
        private final Backend backend = new Backend();
        @Getter
        private final Server server = new Server();
        public final Instant createdAt = Instant.now();

        private volatile Instant terminatedAt = null;

        public GatewayClient(UUID id) {
            this.id = id;
            this.lastDisconnectAt = createdAt;

            this.registerHandler("get-total-player", Void.class, (_res) -> 0L);
            this.registerHandler("login", LoginRequestDto.class, body -> backend.login(id, body));
            this.registerHandler("host", UUID.class, serverId -> backend.host(serverId));
            this.registerHandler("translate", TranslationRequestDto.class, req -> {
                try {
                    return translationService.translate(req.getText(), req.getTargetLang());
                } catch (Exception e) {
                    Log.warn("Translation error: @", e.getMessage());
                    return null;
                }
            });

            this.registerHandler("get-plugin-version", Void.class, _ignore -> {
                return pluginBundleService.getPluginVersion();
            });

            this.registerHandler("download-plugin", Void.class, _ignore -> {
                return new WsRpcChannel.StreamReply(pluginBundleService.downloadPlugin());
            });

            this.registerHandler("event", JsonNode.class, event -> {
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

            if (removed) {
                String message = "Trying to connected to a removed gateway client";
                Log.err(message);
                context.session.close(1, message);
                return;
            }
            // Overwrite wins: a duplicate or reconnect open replaces the socket.
            eventBus.emit(new StartEvent(id));
            rpcChannel.onOpen(new JavalinSession(context));
            lastDisconnectAt = null;

        }

        public synchronized void onClose(WsCloseContext context) {
            Log.info("Gateway client disconnected: " + id);
            
            eventBus.emit(new StopEvent(id, NodeRemoveReason.SOCKET_DISCONNECT));
            lastDisconnectAt = Instant.now();
            rpcChannel.onClose(new RuntimeException("Gateway client disconnected: " + id));
        }

        public boolean isTerminated() {
            return terminatedAt != null;
        }

        public boolean shouldTerminate() {
            if (lastDisconnectAt == null && isSocketClosed()) {
                lastDisconnectAt = Instant.now();
            }

            return lastDisconnectAt != null
                    && Instant.now().isAfter(lastDisconnectAt.plus(TERMINATE_CONNECTION_AFTER))
                    && isSocketClosed();
        }

        private boolean isSocketClosed() {
            WsSession session = rpcChannel.getSession();
            return session == null || !session.isOpen();
        }

        public boolean terminate(NodeRemoveReason reason) {
            removed = true;

            if (isTerminated()) {
                return false;
            }

            terminatedAt = Instant.now();

            try {
                WsSession session = rpcChannel.getSession();

                if (session != null && session.isOpen()) {
                    try {
                        this.server.shutdown().get(5, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        Log.err("Error terminating client: " + id, e);
                    }
                    session.close(WsCloseStatus.NORMAL_CLOSURE.getCode(), "Terminate by server");
                }

                boolean removed = nodeManager.remove(id, reason);

                if (removed || reason == NodeRemoveReason.PROCESS_KILLED) {
                    eventBus.emit(new StopEvent(id, reason));
                    Log.info("[red]Client terminated: " + id);
                }
            } catch (Exception e) {
                Log.err("Error terminating client: " + id, e);
                try {
                    nodeManager.remove(id, reason);
                } catch (Exception e2) {
                    Log.err("Error removing node: " + id, e2);
                }
            }

            return true;
        }

        public void checkDisconnect() {
            if (isTerminated()) {
                return;
            }

            if (lastDisconnectAt != null && Instant.now().isAfter(lastDisconnectAt.plus(DISCONNECT_WARN_AFTER))
                    && isSocketClosed() && nodeManager.isRunning(id)) {
                eventBus.emit(LogEvent.error(id, "Socket disconnected"));
                Log.err("Client socket disconnected: " + id);
            }
        }

        public void onMessage(WsMessageContext context) {
            if (removed) {
                String message = "Trying to send message to a removed gateway client";
                Log.err(message);
                context.session.close(1, message);
                return;
            }

            rpcChannel.onTextMessage(context.message());
        }

        public void onBinary(WsBinaryMessageContext context) {
            if (removed) {
                String message = "Trying to send binary message to a removed gateway client";
                Log.err(message);
                context.session.close(1, message);
                return;
            }

            rpcChannel.onBinaryMessage(ByteBuffer.wrap(context.data()));
        }

        public <Req, Res> void registerHandler(String type, Class<Req> clazz, Function<Req, Res> handler) {
            rpcChannel.registerHandler(type, clazz, handler);
        }

        /** Exposed for tests and adapters. */
        public WsRpcChannel rpcChannel() {
            return rpcChannel;
        }

        public class Backend {
            private final HttpClient httpClient = HttpClients.forUrl(Const.API_URL);

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
                            .timeout(Duration.ofSeconds(10));

                } catch (Exception e) {
                    throw new ApiError(500, "Internal server error", e);
                }
            }

            public LoginDto login(UUID id, LoginRequestDto body) {
                try {
                    HttpRequest request = createRequest("servers", id, "login")
                            .POST(HttpRequest.BodyPublishers.ofString(Utils.toJsonString(body)))
                            .header("Content-Type", "application/json")
                            .build();

                    HttpResponse<String> result = httpClient.send(request, BodyHandlers.ofString());

                    if (result.statusCode() >= 400) {
                        throw new ApiError(result.statusCode(), "Failed to login server: " + result.body());
                    }

                    return Utils.readJsonAsClass(result.body(), LoginDto.class);
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

            public CompletableFuture<Void> updatePlayer(String uuid, LoginDto request) {
                return sendRequest("update-player", request);
            }

            public CompletableFuture<Boolean> pause() {
                return sendRequest("pause", null, Boolean.class);
            }

            public CompletableFuture<ServerStateDto> getState() {
                return sendRequest("get-state", null, ServerStateDto.class);
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

            public CompletableFuture<Void> host(StartServerDto request) {
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

            public CompletableFuture<List<ServerCommandDto>> getCommands() {
                return sendRequest("get-commands", null, JsonNode.class).thenApply(n -> {
                    try {
                        return Utils.getObjectMapper().readerForListOf(ServerCommandDto.class).readValue(n);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
            }

            public CompletableFuture<PlayerInfoPageDto> getPlayersInfo(int page, int size,
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

                return sendRequest("get-players-info", payload, PlayerInfoPageDto.class);
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

            public CompletableFuture<List<RecentPlayerDto>> getRecentPlayers() {
                return sendRequest("get-recent-players", null, JsonNode.class).thenApply(n -> {
                    try {
                        return Utils.getObjectMapper().readerForListOf(RecentPlayerDto.class).readValue(n);
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

            JavalinSession(WsContext socket) {
                this.socket = socket;
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
        }

    }
}
