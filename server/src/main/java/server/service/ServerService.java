package server.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedList;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import arc.files.Fi;
import arc.util.Log;
import dto.MapDto;
import dto.ModDto;
import dto.PlayerDto;
import dto.ServerConfig;
import dto.ServerStateDto;
import dto.ServerStatus;
import events.BaseEvent;
import events.ServerEvents;
import events.ServerEvents.LogEvent;
import enums.NodeRemoveReason;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import server.types.data.NodeUsage;
import server.types.data.ServerMisMatch;
import dto.LoginDto;
import dto.ManagerMapDto;
import dto.ManagerModDto;
import server.manager.NodeManager;
import server.service.GatewayService.GatewayClient;
import server.utils.ApiError;
import server.utils.Utils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

@Slf4j
@Service
@RequiredArgsConstructor
public class ServerService {
    private final GatewayService gatewayService;
    private final NodeManager nodeManager;
    private final EventBus eventBus;
    private final ApiService apiService;
    private final Scheduler scheduler = Schedulers.newSingle("server-service");

    private final ConcurrentHashMap<UUID, EnumSet<ServerFlag>> serverFlags = new ConcurrentHashMap<>();
    private final LinkedList<FluxSink<BaseEvent>> eventSinks = new LinkedList<>();

    private static enum ServerFlag {
        KILL,
        RESTART
    }

    private final ArrayList<BaseEvent> buffer = new ArrayList<>();

    // private static final int MAX_RUNNING_SERVERS = 10;

    @PostConstruct
    private void registerEventHandler() {
        eventBus.on(event -> {
            if (eventSinks.size() == 0) {
                buffer.add(event);

                if (buffer.size() > 1000) {
                    buffer.remove(0);
                }

            } else {
                eventSinks.forEach(sink -> {
                    if (sink != null && !sink.isCancelled())
                        sink.next(event);
                });
            }
        });
    }

    public Flux<BaseEvent> getEvents() {
        Flux<BaseEvent> eventSink = Flux.create(emitter -> {
            emitter.onDispose(() -> {
                eventSinks.remove(emitter);
                Log.info("Client disconnected: " + eventSinks.size());
            });

            ArrayList<BaseEvent> copy = new ArrayList<>(buffer);

            buffer.clear();

            for (BaseEvent event : copy) {
                emitter.next(event);
            }

            if (!emitter.isCancelled()) {
                eventSinks.add(emitter);

                Log.info("Client connected: " + eventSinks.size());
            }
        });

        return eventSink;
    }

    public Mono<Void> remove(UUID serverId, NodeRemoveReason reason) {
        eventBus.emit(new ServerEvents.StopEvent(serverId, reason));

        return nodeManager.remove(serverId, reason);
    }

    public Mono<Boolean> pause(UUID serverId) {
        return gatewayService.of(serverId)//
                .flatMap(client -> client.server().pause());
    }

    public Flux<LogEvent> host(ServerConfig request) {
        var serverId = request.getId();

        Flux<LogEvent> callGatewayFlux = gatewayService.of(serverId)
                .flatMapMany(gatewayClient -> hostCallGateway(request, gatewayClient));

        Flux<LogEvent> createFlux = Flux
                .concat(nodeManager.create(request),
                        Mono.just(LogEvent.info(serverId, "Connecting to gateway...")),
                        callGatewayFlux//
                )
                .onErrorResume(err -> Mono.just(LogEvent.error(serverId, err.getMessage())).then(Mono.error(err)))
                .doOnError(Log::err);

        return Flux.concat(
                Mono.just(LogEvent.info(serverId, "Check if server hosting")), gatewayService.of(serverId)
                        .flatMap(gateway -> gateway.server().isHosting().defaultIfEmpty(false))
                        .flatMapMany(isHosting -> isHosting ? Mono
                                .just(LogEvent.info(serverId, "Server hosting, skip hosting.")) : createFlux));
    }

    private Flux<LogEvent> hostCallGateway(ServerConfig request, GatewayClient gatewayClient) {
        var serverId = request.getId();
        var serverGateway = gatewayClient.server();

        Flux<LogEvent> waitingOkFlux = Flux.concat(//
                Mono.just(LogEvent.info(serverId, "Waiting for server to start")), //
                serverGateway//
                        .isHosting()
                        .retryWhen(Retry.fixedDelay(1200, Duration.ofMillis(100)))
                        .thenReturn(LogEvent.info(serverId, "Server started, waiting for hosting")));

        Flux<LogEvent> hostFlux = serverGateway.isHosting()
                .flatMapMany(isHosting -> {

                    if (isHosting) {
                        var event = LogEvent.info(serverId, "Server is hosting, do nothing");
                        Log.info("Server is hosting, do nothing");
                        return Flux.just(event);
                    }

                    String[] preHostCommand = { //
                            "config name %s".formatted(request.getName()), //
                            request.getDescription().length() == 0 ? ""
                                    : "config desc %s".formatted(request.getDescription()), //
                            "config port 6567", //
                            "version"
                    };

                    Flux<LogEvent> sendCommandFlux = Flux.concat(
                            serverGateway//
                                    .sendCommand(preHostCommand)
                                    .thenReturn(LogEvent.info(serverId, "Config done")));

                    Flux<LogEvent> sendHostFlux = Flux.concat(
                            Mono.just(LogEvent.info(serverId, "Host server")),
                            serverGateway.host(request).then(Mono.empty()));

                    Flux<LogEvent> waitForStatusFlux = Flux.concat(
                            Mono.just(LogEvent.info(serverId, "Wait for server status")),
                            serverGateway.isHosting()//
                                    .flatMap(b -> b //
                                            ? Mono.empty()
                                            : ApiError.badRequest("Server is not hosting yet"))//
                                    .retryWhen(Retry.fixedDelay(600, Duration.ofMillis(100)))
                                    .onErrorMap(IllegalStateException.class,
                                            error -> new ApiError(HttpStatus.BAD_GATEWAY, "Can not host server"))
                                    .thenReturn(LogEvent.info(serverId, "Server hosting")));

                    return Flux.concat(sendCommandFlux, sendHostFlux, waitForStatusFlux);
                });

        return Flux.concat(waitingOkFlux, hostFlux);
    }

    public Flux<ServerMisMatch> getMismatch(UUID serverId, ServerConfig config) {
        return Mono.zipDelayError(//
                state(serverId), //
                getMods(serverId).filter(mod -> !mod.getName().equals("PluginLoader")).collectList()//
        ).flatMapMany(zip -> {
            var state = zip.getT1();
            var mods = zip.getT2();

            return nodeManager.getMismatch(serverId, config, state, mods);
        });
    }

    public Flux<ManagerMapDto> getManagerMaps() {
        return nodeManager.getManagerMaps();
    }

    public Flux<ManagerModDto> getManagerMods() {
        return nodeManager.getManagerMods();
    }

    public Flux<MapDto> getMaps(UUID serverId) {
        return nodeManager.getMaps(serverId);
    }

    public Flux<ModDto> getMods(UUID serverId) {
        return nodeManager.getMods(serverId);
    }

    public Mono<ResponseEntity<Object>> getFiles(UUID serverId, String path) {
        return nodeManager.getFiles(serverId, path);
    }

    public Mono<Boolean> isFileExists(UUID serverId, String path) {
        return nodeManager.getFile(serverId, path).map(Fi::exists);
    }

    public Mono<Void> writeFile(UUID serverId, String path, FilePart filePart) {
        if (filePart == null) {
            return nodeManager.writeFile(serverId, path, new byte[0]);
        }

        return Utils.readAllBytes(filePart)
                .flatMap(bytes -> nodeManager.writeFile(serverId, path, bytes)
                        .then(Mono.fromRunnable(() -> {
                            String filename = filePart.filename();

                            if (filename.endsWith("msav")) {
                                apiService.getMapPreview(bytes)
                                        .map(image -> Utils.toByteArray(Utils.toPreviewImage(Utils.fromBytes(image))))
                                        .flatMap(image -> nodeManager.writeFile(serverId, path + ".png", image))
                                        .subscribeOn(Schedulers.boundedElastic())
                                        .doOnError(Log::err)
                                        .onErrorComplete()
                                        .subscribe();
                            }
                        })));
    }

    public Mono<Boolean> createFolder(UUID serverId, String path) {
        return nodeManager.createFolder(serverId, path);
    }

    public Mono<Boolean> deleteFile(UUID serverId, String path) {
        return nodeManager.deleteFile(serverId, path);
    }

    public Flux<NodeUsage> getUsage(UUID serverId) {
        return nodeManager.getNodeUsage(serverId);
    }

    public Mono<ServerStateDto> state(UUID serverId) {
        return gatewayService.of(serverId)//
                .flatMap(client -> client.server().getState())//
                .onErrorResume(error -> {
                    Log.err(error.getMessage());
                    return Mono.empty();
                })
                .defaultIfEmpty(new ServerStateDto().setServerId(serverId).setStatus(ServerStatus.PAUSED));
    }

    public Mono<byte[]> getImage(UUID serverId) {
        return gatewayService.of(serverId)//
                .flatMap(client -> client.server().getImage());
    }

    public Flux<PlayerDto> getPlayers(UUID serverId) {
        return gatewayService.of(serverId)
                .flatMap(client -> client.server().getState())
                .flatMapIterable(state -> state.getPlayers());
    }

    public Mono<Void> updatePlayer(UUID serverId, String uuid, LoginDto payload) {
        return gatewayService.of(serverId).flatMap(client -> client.server().updatePlayer(uuid, payload));
    }

    @PostConstruct
    @Scheduled(fixedDelay = 5, timeUnit = TimeUnit.SECONDS)
    private void autoConnectAndHostCron() {
        nodeManager.list()
                .filter(state -> state.meta.isPresent())
                .flatMap(state -> {
                    if (state.running()) {
                        return gatewayService.of(state.meta().get().getConfig().getId());
                    }

                    var config = state.meta().get().getConfig();

                    if (config.getIsAutoTurnOff() == false) {
                        return host(config);
                    }

                    return Mono.empty();
                })
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
    }

    @PostConstruct
    @Scheduled(fixedDelay = 10, timeUnit = TimeUnit.MINUTES)
    private void autoTurnOfCron() {
        // var runningWithAutoTurnOff = containers.stream()//
        // .filter(container -> container.getState().equalsIgnoreCase("running"))//
        // .filter(container ->
        // readMetadataFromContainer(container).map(ServerMetadata::getInit).map(
        // ServerConfig::isAutoTurnOff).orElse(false))//
        // .collect(Collectors.toList())
        // .size();

        // var shouldAutoTurnOff = runningWithAutoTurnOff > 2;

        nodeManager.list()
                .filter(state -> state.meta.isPresent() && state.running())
                .map(state -> state.meta().orElseThrow().getConfig())
                .collectList()
                .flatMapMany(servers -> {
                    // boolean shouldAutoTurnOff = servers.size() > MAX_RUNNING_SERVERS;

                    return Flux.fromIterable(servers)
                            .flatMap(config -> checkRunningServer(config, true));
                })
                .doOnError(error -> Log.err(error.getMessage()))
                .onErrorComplete(ApiError.class)
                .timeout(Duration.ofSeconds(10))
                .subscribeOn(scheduler)
                .subscribe();
    }

    private Mono<Void> checkRunningServer(ServerConfig config, boolean shouldAutoTurnOff) {

        if (config.getIsAutoTurnOff() == false) {
            return Mono.empty();
        }

        var serverId = config.getId();
        var flag = serverFlags.computeIfAbsent(serverId, (_ignore) -> EnumSet.noneOf(ServerFlag.class));

        Log.info("Check server @ with flag @", config, flag);

        return gatewayService.of(serverId)//
                .flatMap(client -> client.server().getState())//
                .defaultIfEmpty(new ServerStateDto().setServerId(serverId).setStatus(ServerStatus.NOT_RESPONSE))
                .flatMap(state -> {
                    boolean shouldKill = state.getPlayers().isEmpty();

                    if (shouldKill && shouldAutoTurnOff) {
                        if (flag.contains(ServerFlag.KILL)) {
                            flag.remove(ServerFlag.KILL);

                            eventBus.emit(LogEvent.info(serverId, "[red][Orchestrator] Auto shut down server"));

                            return remove(serverId, NodeRemoveReason.NO_PLAYER);
                        } else {
                            flag.add(ServerFlag.KILL);

                            eventBus.emit(LogEvent.info(serverId, "[red][Orchestrator] No players, flag to kill"));
                        }
                    } else {
                        if (flag.contains(ServerFlag.KILL)) {
                            flag.remove(ServerFlag.KILL);

                            eventBus.emit(
                                    LogEvent.info(serverId, "[green][Orchestrator] Remove kill flag from server"));

                        }
                    }

                    return Mono.empty();
                })//
                .doOnError(error -> log.error(error.getMessage()))
                .onErrorComplete();
    }
}
