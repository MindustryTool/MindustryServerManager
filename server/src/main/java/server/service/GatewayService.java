package server.service;

import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;

import com.fasterxml.jackson.databind.JsonNode;

import arc.util.Log;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import server.EnvConfig;
import server.config.Const;
import server.manager.NodeManager;
import dto.LoginDto;
import dto.PlayerDto;
import dto.PlayerInfoDto;
import dto.ServerCommandDto;
import dto.ServerConfig;
import dto.ServerStateDto;
import events.BaseEvent;
import events.ServerEvents;
import events.ServerEvents.DisconnectEvent;
import events.ServerEvents.LogEvent;
import events.ServerEvents.StartEvent;
import enums.NodeRemoveReason;
import events.ServerEvents.StopEvent;
import jakarta.annotation.PreDestroy;
import server.utils.ApiError;
import server.utils.Utils;
import reactor.core.Disposable;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

@Slf4j
@Service
@RequiredArgsConstructor
public class GatewayService {

    private final EnvConfig envConfig;
    private final EventBus eventBus;
    private final NodeManager nodeManager;
    private final ConcurrentHashMap<UUID, Mono<GatewayClient>> cache = new ConcurrentHashMap<>();

    @Getter
    private final Api api = new Api();

    public Mono<Boolean> isHosting(UUID serverId) {
        if (cache.containsKey(serverId)) {
            return cache.get(serverId)
                    .flatMap(gateway -> gateway.server().isHosting());
        }

        return Mono.just(false);
    }

    public synchronized Mono<GatewayClient> of(UUID serverId) {
        return cache.computeIfAbsent(serverId,
                _id -> Mono.<GatewayClient>create(
                        (emittor) -> new GatewayClient(serverId, emittor::success, emittor::error))
                        .cache());
    }

    @PreDestroy
    private void cancelAll() {
        Log.info("Cancel all GatewayClient");
        cache.values()
                .forEach(mono -> mono
                        .doOnError(ApiError.class, error -> Log.err(error.getMessage()))
                        .onErrorComplete(ApiError.class)
                        .block(Duration.ofSeconds(5)).cancel());
    }

    public class Api {
        private WebClient webClient(UUID id) {
            return WebClient.builder()
                    .codecs(configurer -> configurer
                            .defaultCodecs()
                            .maxInMemorySize(16 * 1024 * 1024))
                    .baseUrl(URI.create(Const.API_URL).toString())
                    .defaultHeaders(headers -> {
                        headers.set("X-SERVER-ID", id.toString());
                        headers.set("X-MANAGER-AUTH", envConfig.serverConfig().accessToken());
                    })
                    .defaultStatusHandler(Utils::handleStatus, Utils::createError)
                    .build();
        }

        public Mono<JsonNode> login(UUID serverId, JsonNode body) {
            return Utils.wrapError(webClient(serverId).method(HttpMethod.POST)
                    .uri("/servers/" + serverId + "/login")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class), Duration.ofSeconds(2), "Login");
        }

        public Mono<String> host(UUID serverId) {
            Log.info("Host server: " + serverId);
            return Utils.wrapError(webClient(serverId).method(HttpMethod.POST)
                    .uri("/servers/" + serverId + "/host-server")
                    .retrieve()
                    .bodyToMono(String.class), Duration.ofSeconds(60), "Host server");
        }
    }

    @RequiredArgsConstructor
    @Accessors(fluent = true)
    public class GatewayClient {

        private static final Duration HEARTBEAT_TIMEOUT_DURATION = Duration.ofSeconds(20);

        enum ConnectionState {
            CONNECTED, DISCONNECTED, CONNECTING
        }

        @Getter
        private final UUID id;

        @Getter
        private final Server server;

        private final Instant createdAt = Instant.now();
        private Instant disconnectedAt = null;
        private ConnectionState state = ConnectionState.CONNECTING;
        private Instant lastHeartBeatAt = Instant.now();

        private final Disposable eventJob;
        private final Disposable heartbeatJob;

        public GatewayClient(UUID id, Consumer<GatewayClient> onConnect, Consumer<Throwable> onError) {
            this.id = id;
            this.server = new Server();
            this.eventJob = createFetchEventJob(onConnect, onError);
            this.heartbeatJob = createHeartbeatJob();

            Log.info("Create GatewayClient for server: " + id);
        }

        public ConnectionState state() {
            return state;
        }

        public void cancel() {
            eventJob.dispose();
            heartbeatJob.dispose();
        }

        public class Server {
            private final WebClient webClient = WebClient.builder()
                    .codecs(configurer -> configurer
                            .defaultCodecs()
                            .maxInMemorySize(16 * 1024 * 1024))
                    .baseUrl(URI.create(
                            Const.IS_DEVELOPMENT//
                                    ? "http://localhost:9999/" //
                                    : "http://" + id.toString() + ":9999/")
                            .toString())
                    .defaultHeaders(headers -> {
                        headers.set("X-SERVER-ID", id.toString());
                        headers.set("X-CREATED-AT", createdAt.toString());
                    })
                    .defaultStatusHandler(Utils::handleStatus, Utils::createError)
                    .build();

            public Mono<JsonNode> getJson() {
                return Utils.wrapError(webClient.method(HttpMethod.GET)//
                        .uri("json")//
                        .retrieve()//
                        .bodyToMono(JsonNode.class), Duration.ofSeconds(5), "Get json");
            }

            public Mono<String> getPluginVersion() {
                return Utils.wrapError(webClient.method(HttpMethod.GET)//
                        .uri("plugin-version")//
                        .retrieve()//
                        .bodyToMono(String.class), Duration.ofSeconds(5), "Get plugin version");
            }

            public Mono<Void> updatePlayer(String uuid, LoginDto request) {
                return Utils.wrapError(webClient.method(HttpMethod.PUT)//
                        .uri("players/" + uuid)//
                        .bodyValue(request)//
                        .retrieve()//
                        .bodyToMono(String.class), Duration.ofSeconds(5), "Set player")
                        .then();
            }

            public Mono<Boolean> pause() {
                return Utils.wrapError(webClient.method(HttpMethod.POST)
                        .uri("pause")
                        .retrieve()
                        .bodyToMono(Boolean.class), Duration.ofSeconds(5), "Pause");
            }

            public Flux<PlayerDto> getPlayers() {
                return Utils.wrapError(
                        webClient.method(HttpMethod.GET)
                                .uri("players")
                                .retrieve()
                                .bodyToFlux(PlayerDto.class),
                        Duration.ofSeconds(5), "Get players");
            }

            public Mono<ServerStateDto> getState() {
                return Utils.wrapError(
                        webClient.method(HttpMethod.GET)
                                .uri("state")
                                .retrieve()
                                .bodyToMono(ServerStateDto.class),
                        Duration.ofSeconds(2),
                        "Get state");
            }

            public Mono<byte[]> getImage() {
                return Utils.wrapError(
                        webClient.method(HttpMethod.GET)
                                .uri("image")
                                .accept(MediaType.IMAGE_PNG)
                                .retrieve()
                                .bodyToMono(byte[].class),
                        Duration.ofSeconds(5),
                        "Get image");
            }

            public Mono<Void> sendCommand(String... command) {
                return Utils.wrapError(
                        webClient.method(HttpMethod.POST)
                                .uri("commands")
                                .bodyValue(command)
                                .retrieve()
                                .bodyToMono(Void.class),
                        Duration.ofSeconds(2),
                        "Send command");
            }

            public Mono<Void> say(String message) {
                return Utils.wrapError(
                        webClient.method(HttpMethod.POST)
                                .uri("say")
                                .bodyValue(message)
                                .retrieve()
                                .bodyToMono(Void.class),
                        Duration.ofSeconds(2),
                        "Say message");
            }

            public Mono<Void> host(ServerConfig request) {
                return Utils.wrapError(
                        webClient.method(HttpMethod.POST)
                                .uri("host")
                                .bodyValue(request)
                                .retrieve()
                                .bodyToMono(Void.class),
                        Duration.ofSeconds(15),
                        "Host server");
            }

            public Mono<Void> sendChat(JsonNode request) {
                return Utils.wrapError(
                        webClient.method(HttpMethod.POST)
                                .uri("chat")
                                .bodyValue(request)
                                .retrieve()
                                .bodyToMono(Void.class),
                        Duration.ofSeconds(1), "Send chat");
            }

            public Mono<Boolean> isHosting() {
                return Utils.wrapError(webClient.method(HttpMethod.GET)
                        .uri("hosting")
                        .retrieve()
                        .bodyToMono(Boolean.class), Duration.ofMillis(100), "Check hosting");
            }

            public Flux<ServerCommandDto> getCommands() {
                return Utils.wrapError(webClient.method(HttpMethod.GET)
                        .uri("commands")
                        .retrieve()
                        .bodyToFlux(ServerCommandDto.class), Duration.ofSeconds(10), "Get commands");
            }

            public Flux<PlayerInfoDto> getPlayers(int page, int size, Boolean banned, String filter) {
                return Utils.wrapError(
                        webClient.method(HttpMethod.GET)
                                .uri(builder -> builder.path("players-info")
                                        .queryParam("page", page)
                                        .queryParam("size", size)
                                        .queryParam("banned", banned)
                                        .queryParam("filter", filter)
                                        .build())
                                .retrieve()
                                .bodyToFlux(PlayerInfoDto.class),
                        Duration.ofSeconds(10),
                        "Get player infos");
            }

            public Mono<Map<String, Long>> getKickedIps() {
                return Utils.wrapError(
                        webClient.method(HttpMethod.GET)
                                .uri("kicked-ips")
                                .retrieve()
                                .bodyToMono(new ParameterizedTypeReference<Map<String, Long>>() {
                                }),
                        Duration.ofSeconds(10),
                        "Get kicked IPs");
            }

            public Mono<JsonNode> getRoutes() {
                return Utils.wrapError(
                        webClient.method(HttpMethod.GET)
                                .uri("routes")
                                .retrieve()
                                .bodyToMono(JsonNode.class),
                        Duration.ofSeconds(10),
                        "Get routes");
            }

            public Mono<JsonNode> getWorkflowNodes() {
                return Utils.wrapError(
                        webClient.method(HttpMethod.GET)
                                .uri("workflow/nodes")
                                .retrieve()
                                .bodyToMono(JsonNode.class),
                        Duration.ofSeconds(10),
                        "Get workflow nodes");
            }

            public Flux<JsonNode> getWorkflowEvents() {
                return Utils.wrapError(
                        webClient.method(HttpMethod.GET)
                                .uri("workflow/events")
                                .accept(MediaType.TEXT_EVENT_STREAM)
                                .retrieve()
                                .bodyToFlux(JsonNode.class),
                        Duration.ofSeconds(10),
                        "Get workflow events").log();
            }

            public Mono<JsonNode> emitWorkflowNode(String nodeId) {
                return Utils.wrapError(webClient.method(HttpMethod.GET)
                        .uri("workflow/emit/" + nodeId)
                        .retrieve()
                        .bodyToMono(JsonNode.class), Duration.ofSeconds(10), "Emit workflow node");
            }

            public Mono<Long> getWorkflowVersion() {
                return Utils.wrapError(webClient.method(HttpMethod.GET)
                        .uri("/workflow/version")
                        .retrieve()
                        .bodyToMono(Long.class), Duration.ofSeconds(10), " Get workflow version");
            }

            public Mono<JsonNode> getWorkflow() {
                return Utils.wrapError(webClient.method(HttpMethod.GET)
                        .uri("/workflow")
                        .retrieve()
                        .bodyToMono(JsonNode.class), Duration.ofSeconds(10), "Get workflow");
            }

            public Mono<Void> saveWorkflow(JsonNode payload) {
                return Utils.wrapError(webClient.method(HttpMethod.POST)
                        .uri("/workflow")
                        .bodyValue(payload)
                        .retrieve()
                        .bodyToMono(Void.class), Duration.ofSeconds(10), "Save workflow");
            }

            public Mono<JsonNode> loadWorkflow(JsonNode payload) {
                return Utils.wrapError(webClient.method(HttpMethod.POST)
                        .uri("/workflow/load")
                        .bodyValue(payload)
                        .retrieve()
                        .bodyToMono(JsonNode.class), Duration.ofSeconds(10), "Load workflow");
            }

            public Flux<JsonNode> getEvents() {
                return webClient.get()
                        .uri("events")
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .retrieve()
                        .bodyToFlux(JsonNode.class)
                        .concatWith(Mono.error(
                                new ApiError(HttpStatus.REQUEST_TIMEOUT,
                                        "SSE stream completed, This should not happend")));
            }
        }

        private Disposable createFetchEventJob(Consumer<GatewayClient> onConnect, Consumer<Throwable> onError) {
            return this.server.getEvents()
                    .doOnNext(event -> {
                        if (state != ConnectionState.CONNECTED) {
                            state = ConnectionState.CONNECTED;
                            disconnectedAt = null;
                            eventBus.emit(new StartEvent(id));
                            onConnect.accept(this);
                        }

                        try {
                            var name = event.get("name").asText(null);

                            if (name == null) {
                                Log.warn("Invalid event: " + event.asText());
                            }

                            if (name == "heartbeat") {
                                lastHeartBeatAt = Instant.now();
                                return;
                            }

                            var eventType = ServerEvents.getEventMap().get(name);

                            if (eventType == null) {
                                Log.warn("Invalid event name: " + name + " in " + ServerEvents.getEventMap().keySet());
                            }

                            BaseEvent data = (BaseEvent) Utils.readJsonAsClass(event, eventType);
                            eventBus.emit(data);

                        } catch (Exception e) {
                            Log.err(e);
                        }
                    })
                    .onErrorMap(WebClientRequestException.class, error -> {
                        if (error.getCause() instanceof UnknownHostException) {
                            return new ApiError(HttpStatus.NOT_FOUND, "Server not found: " + error.getMessage());
                        }

                        if (Utils.isConnectionException(error.getCause())) {
                            return new ApiError(HttpStatus.NOT_FOUND, "Can not connect: " + error.getMessage());
                        }

                        return error;
                    })
                    .doOnError(error -> {
                        if (disconnectedAt == null) {
                            disconnectedAt = Instant.now();
                        }

                        if (state != ConnectionState.DISCONNECTED) {
                            state = ConnectionState.DISCONNECTED;

                            eventBus.emit(new DisconnectEvent(id));
                        }
                    })
                    .onErrorComplete(error -> error instanceof ApiError apiError && apiError.status.is5xxServerError())
                    .retryWhen(Retry.fixedDelay(60, Duration.ofSeconds(1)))
                    .onErrorMap(Exceptions::isRetryExhausted,
                            error -> new ApiError(HttpStatus.BAD_REQUEST, "Events timeout: " + error.getMessage()))
                    .doOnError(_ignore -> {
                        nodeManager.remove(id, NodeRemoveReason.FETCH_EVENT_TIMEOUT)
                                .doOnError(ApiError.class, error -> Log.err(error.getMessage()))
                                .onErrorComplete(ApiError.class)
                                .subscribe();

                        eventBus.emit(new StopEvent(id, NodeRemoveReason.FETCH_EVENT_TIMEOUT));
                    })
                    .doOnError(error -> Log.err(error.getMessage()))
                    .doOnError((error) -> onError.accept(error))
                    .onErrorComplete(ApiError.class)
                    .doFinally(signal -> {
                        cache.remove(id);

                        Log.info("Close GatewayClient: " + id + " with signal: " + signal);
                        Log.info("Running for: " + Utils.toReadableString(Duration.between(createdAt, Instant.now())));

                        if (disconnectedAt != null) {
                            Log.info(
                                    "Disconnected for: "
                                            + Utils.toReadableString(Duration.between(disconnectedAt, Instant.now())));
                        }

                    })
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe();
        }

        private Disposable createHeartbeatJob() {
            return Flux.interval(HEARTBEAT_TIMEOUT_DURATION)
                    .doOnNext(value -> {
                        if (Instant.now().isAfter(lastHeartBeatAt.plus(HEARTBEAT_TIMEOUT_DURATION))) {
                            Log.warn("Heartbeat timeout for client: " + id);
                            eventBus.emit(LogEvent.error(id, "Heartbeat timeout"));
                        }
                    })
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe();
        }
    }
}
