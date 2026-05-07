package server.service;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import org.apache.hc.core5.net.URIBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import arc.util.Log;
import lombok.Getter;
import lombok.experimental.Accessors;
import dto.LoginDto;
import dto.LoginRequestDto;
import dto.PlayerInfoDto;
import dto.ServerCommandDto;
import dto.ServerConfig;
import dto.ServerStateDto;
import dto.WsMessage;
import enums.NodeRemoveReason;
import dto.MessageHandler;
import events.BaseEvent;
import events.ServerEvents;
import events.ServerEvents.DisconnectEvent;
import events.ServerEvents.LogEvent;
import events.ServerEvents.StartEvent;
import events.ServerEvents.StopEvent;
import io.javalin.websocket.WsCloseStatus;
import io.javalin.websocket.WsContext;
import io.javalin.websocket.WsMessageContext;
import server.EnvConfig;
import server.config.Const;
import server.manager.NodeManager;
import server.utils.ApiError;
import server.utils.Utils;
import com.fasterxml.jackson.core.type.TypeReference;

public class GatewayService {

    private final EventBus eventBus;
    private final EnvConfig envConfig;
    private final NodeManager nodeManager;
    private final ConcurrentHashMap<UUID, GatewayClient> clients = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public GatewayService(EventBus eventBus, EnvConfig envConfig, NodeManager nodeManager) {
        this.eventBus = eventBus;
        this.envConfig = envConfig;
        this.nodeManager = nodeManager;

        scheduler.scheduleWithFixedDelay(() -> {
            clients.values().removeIf(client -> {
                if (client.shouldTerminate()) {
                    client.terminate();
                    return true;
                }
                return false;
            });

            clients.values().forEach(GatewayClient::checkHeartbeat);
        }, 15, 15, TimeUnit.SECONDS);
    }

    public GatewayClient of(UUID serverId) {
        return clients.computeIfAbsent(serverId, _ignore -> new GatewayClient(serverId));
    }

    @Accessors(fluent = true)
    public class GatewayClient {
        private static final Duration HEARTBEAT_TIMEOUT_DURATION = Duration.ofSeconds(30);
        private static final Duration TERMINATE_CONNECTION_AFTER = Duration.ofMinutes(2);

        private final HashMap<String, MessageHandler<Object, Object>> messageHandlers = new HashMap<>();

        @Getter
        private final UUID id;
        private WsContext context;

        private volatile Instant lastHeartBeatAt = Instant.now();
        private CompletableFuture<Void> connectedFuture = new CompletableFuture<>();

        private final Map<UUID, CompletableFuture<JsonNode>> pendingRequests = new ConcurrentHashMap<>();

        @Getter
        private final Backend backend = new Backend();
        @Getter
        private final Server server = new Server();
        public final Instant createdAt = Instant.now();

        public GatewayClient(UUID id) {
            this.id = id;

            this.registerMessageHandler("get-total-player", Void.class, (_res) -> 0L);
            this.registerMessageHandler("login", LoginRequestDto.class, body -> backend.login(id, body));
            this.registerMessageHandler("host", UUID.class, serverId -> backend.host(serverId));
            this.registerMessageHandler("event", JsonNode.class, event -> {
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

        public void setSocketContext(WsContext context) {
            this.context = context;

            if (context == null) {
                if (nodeManager.isRunning(id)) {
                    eventBus.emit(new DisconnectEvent(id));
                } else {
                    eventBus.emit(new StopEvent(id, NodeRemoveReason.UNKNOWN));
                }
                connectedFuture.completeExceptionally(
                        new RuntimeException("Disconnected"));
                connectedFuture = new CompletableFuture<>();
                Log.err("Gateway client disconnected: " + id);
                return;
            } else {
                eventBus.emit(new StartEvent(id));
                connectedFuture.complete(null);
                Log.info("Gateway client connected: " + id);
            }
        }

        public boolean shouldTerminate() {
            return Instant.now().isAfter(lastHeartBeatAt.plus(TERMINATE_CONNECTION_AFTER));
        }

        public void terminate() {
            if (context != null) {
                context.closeSession(WsCloseStatus.NORMAL_CLOSURE, "Closed due to inactivity");
            }

            nodeManager.remove(id, NodeRemoveReason.NOT_CONNECTED);

            Log.info("[red]Client terminated: " + id);
        }

        public void checkHeartbeat() {
            if (Instant.now().isAfter(lastHeartBeatAt.plus(HEARTBEAT_TIMEOUT_DURATION))) {
                eventBus.emit(LogEvent.error(id, "Heartbeat timeout"));
                Log.err("Client heartbeat timeout: " + id);
            }
        }

        public void onMessage(WsMessageContext context) {
            JsonNode json = context.messageAsClass(JsonNode.class);
            JsonNode payload = json.get("payload");
            WsMessage<?> wsMessage = context.messageAsClass(WsMessage.class);

            lastHeartBeatAt = Instant.now();

            if (wsMessage.getResponseOf() != null) {
                CompletableFuture<JsonNode> future = pendingRequests.remove(wsMessage.getResponseOf());
                if (future == null) {
                    Log.warn("No future found for responseOf: @", wsMessage.getResponseOf());
                    return;
                }
                if (wsMessage.isError()) {
                    future.completeExceptionally(new RuntimeException(payload.toString()));
                } else {
                    future.complete(payload);
                }
                return;
            }

            MessageHandler<Object, Object> handler = messageHandlers.get(wsMessage.getType());

            if (handler != null) {
                try {
                    Object param = Utils.readJsonAsClass(payload, handler.getClazz());
                    Object result = handler.getFn().apply(param);
                    WsMessage<?> response = wsMessage.response(result);
                    context.send(response);
                } catch (Exception e) {
                    Log.err("Error handling message: " + wsMessage, e);
                    WsMessage<?> error = wsMessage.error(e.getMessage());
                    context.send(error);
                }
            }
        }

        @SuppressWarnings("unchecked")
        public <Req, Res> void registerMessageHandler(String type, Class<Req> clazz, Function<Req, Res> handler) {
            MessageHandler<Object, Object> mh = new MessageHandler<Object, Object>((Class<Object>) clazz,
                    (Function<Object, Object>) handler);
            messageHandlers.put(type, mh);
        }

        public class Backend {
            private final HttpClient httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();

            private HttpRequest.Builder createRequest(Object... segments) {
                try {
                    String[] str = new String[segments.length];

                    for (int i = 0; i < segments.length; i++) {
                        str[i] = segments[i].toString();
                    }

                    return HttpRequest.newBuilder()
                            .uri(new URIBuilder(Const.API_URL + "/" + String.join("/", str)).build())
                            .header("X-SERVER-ID", id.toString())
                            .header("X-MANAGER-AUTH", envConfig.serverConfig().accessToken());
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
                if (context == null) {
                    try {
                        connectedFuture.get(1, TimeUnit.MINUTES);
                    } catch (InterruptedException | ExecutionException | TimeoutException e) {
                        return CompletableFuture
                                .failedFuture(new RuntimeException("Server gateway not connected: " + id, e));
                    }
                }

                WsMessage<?> request = WsMessage.create(type).withPayload(payload);

                CompletableFuture<JsonNode> future = new CompletableFuture<>();
                pendingRequests.put(request.getId(), future);

                future.orTimeout(1, TimeUnit.MINUTES);
                future.whenComplete((_res, _err) -> pendingRequests.remove(request.getId()));

                try {
                    context.send(request);
                } catch (Exception e) {
                    pendingRequests.remove(request.getId());
                    future.completeExceptionally(e);
                }

                return future.thenApply(r -> Utils.readJsonAsClass(r, clazz));
            }

            private CompletableFuture<Void> sendRequest(String type, Object payload) {
                return sendRequest(type, payload, Void.class);
            }

            public CompletableFuture<JsonNode> getJson() {
                return sendRequest("get-json", null, JsonNode.class);
            }

            public CompletableFuture<String> getPluginVersion() {
                return sendRequest("get-plugin-version", null, String.class);
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
                        .thenApply(res -> nodeManager.getFile(id, "map-preview-image.png").readBytes());
            }

            public CompletableFuture<Void> sendCommand(String... command) {
                return sendRequest("send-command", command);
            }

            public CompletableFuture<Void> say(String message) {
                return sendRequest("say", message);
            }

            public CompletableFuture<Void> host(ServerConfig request) {
                return sendRequest("host", request);
            }

            public CompletableFuture<Void> sendChat(JsonNode request) {
                return sendRequest("chat", request);
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

            public CompletableFuture<List<PlayerInfoDto>> getPlayersInfo(int page, int size,
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

                return sendRequest("get-players-info", payload, JsonNode.class).thenApply(n -> {
                    try {
                        return Utils.getObjectMapper().readerForListOf(PlayerInfoDto.class).readValue(n);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
            }

            public CompletableFuture<Map<String, Long>> getKickedIps() {
                return sendRequest("get-kicked-ips", null)
                        .thenApply(n -> Utils.getObjectMapper().convertValue(n, new TypeReference<>() {
                        }));
            }
        }
    }
}
