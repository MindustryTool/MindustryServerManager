package server.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedList;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.springframework.http.HttpStatus;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import arc.files.Fi;
import arc.util.Log;
import dto.MapDto;
import dto.ModDto;
import dto.ServerStateDto;
import dto.ServerStatus;
import events.BaseEvent;
import events.LogEvent;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import server.types.data.NodeUsage;
import server.types.data.ServerConfig;
import server.types.data.ServerMisMatch;
import dto.ManagerMapDto;
import dto.ManagerModDto;
import dto.MindustryToolPlayerDto;
import server.manager.NodeManager;
import server.service.GatewayService.GatewayClient;
import server.utils.ApiError;
import server.utils.Utils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
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

    private final ConcurrentHashMap<UUID, EnumSet<ServerFlag>> serverFlags = new ConcurrentHashMap<>();
    private final LinkedList<FluxSink<BaseEvent>> eventSinks = new LinkedList<>();

    private static enum ServerFlag {
        KILL,
        RESTART
    }

    private final ArrayList<BaseEvent> buffer = new ArrayList<>();

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
                    if (!sink.isCancelled())
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

            eventSinks.add(emitter);

            Log.info("Client connected: " + eventSinks.size());
        });

        return eventSink;
    }

    @Scheduled(fixedDelay = 5, timeUnit = TimeUnit.SECONDS)
    @PostConstruct
    private void scanServer() {
        nodeManager.list()
                .filter(state -> state.meta.isPresent() && state.running())
                .flatMap(state -> gatewayService.of(state.meta.get().getConfig().getId()))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnError(ApiError.class, error -> Log.err(error.getMessage()))
                .onErrorComplete(ApiError.class)
                .subscribe();
    }

    @Scheduled(fixedDelay = 5, timeUnit = TimeUnit.MINUTES)
    private void cron() {
        // var runningWithAutoTurnOff = containers.stream()//
        // .filter(container -> container.getState().equalsIgnoreCase("running"))//
        // .filter(container ->
        // readMetadataFromContainer(container).map(ServerMetadata::getInit).map(
        // ServerConfig::isAutoTurnOff).orElse(false))//
        // .collect(Collectors.toList())
        // .size();

        // var shouldAutoTurnOff = runningWithAutoTurnOff > 2;

        nodeManager.list()
                .flatMap(state -> {
                    if (state.running()) {
                        return checkRunningServer(state.meta().orElseThrow().getConfig(), true);
                    }

                    return Mono.empty();
                })
                .doOnError(ApiError.class, error -> Log.err(error.getMessage()))
                .onErrorComplete(ApiError.class)
                .timeout(Duration.ofMinutes(5))
                .subscribeOn(Schedulers.boundedElastic())
                .blockLast();
    }

    public Mono<Void> remove(UUID serverId) {
        return nodeManager.remove(serverId);
    }

    public Mono<Boolean> pause(UUID serverId) {
        return gatewayService.of(serverId)//
                .flatMap(client -> client.getServer().pause());
    }

    public Flux<LogEvent> host(ServerConfig request) {
        var serverId = request.getId();

        Flux<LogEvent> callGatewayFlux = gatewayService.of(serverId)
                .flatMapMany(gatewayClient -> hostCallGateway(request, gatewayClient));

        return Flux.concat(nodeManager.create(request), callGatewayFlux)
                .onErrorResume(err -> Mono.just(LogEvent.error(serverId, err.getMessage())).then(Mono.error(err)))
                .doOnError(Log::err);
    }

    private Flux<LogEvent> hostCallGateway(ServerConfig request, GatewayClient gatewayClient) {
        var serverId = request.getId();
        var serverGateway = gatewayClient.getServer();

        Flux<LogEvent> waitingOkFlux = Flux.concat(//
                Mono.just(LogEvent.info(serverId, "Waiting for server to start")), //
                serverGateway//
                        .isHosting()
                        .retryWhen(Retry.fixedDelay(400, Duration.ofMillis(100)))
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
                            "config desc %s".formatted(request.getDescription()), //
                            "config port 6567", //
                            "version"
                    };

                    Flux<LogEvent> sendCommandFlux = Flux.concat(
                            Flux.fromArray(preHostCommand)
                                    .map(cmd -> LogEvent.info(serverId, cmd)),
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
                getMods(serverId).collectList()//
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

    public Object getFiles(UUID serverId, String path) {
        return nodeManager.getFiles(serverId, path);
    }

    public Mono<Boolean> isFileExists(UUID serverId, String path) {
        return nodeManager.getFile(serverId, path).map(Fi::exists);
    }

    public Mono<Void> writeFile(UUID serverId, String path, FilePart filePart) {

        return Utils.readAllBytes(filePart)
                .flatMap(bytes -> nodeManager.writeFile(serverId, path, bytes).then(Mono.fromRunnable(() -> {
                    String filename = filePart.filename();

                    if (filename.endsWith("msav")) {
                        apiService.getMapPreview(bytes)
                                .flatMap(previewBytes -> nodeManager.writeFile(serverId, path + ".png", previewBytes))
                                .subscribeOn(Schedulers.boundedElastic())
                                .doOnError(Log::err)
                                .onErrorComplete()
                                .subscribe();
                    }
                })));
    }

    public Mono<Boolean> deleteFile(UUID serverId, String path) {
        return nodeManager.deleteFile(serverId, path);
    }

    public Flux<NodeUsage> getUsage(UUID serverId) {
        return nodeManager.getNodeUsage(serverId);
    }

    public Mono<ServerStateDto> state(UUID serverId) {
        return gatewayService.of(serverId)//
                .flatMap(client -> client.getServer().getState())//
                .onErrorResume(error -> {
                    Log.err(error.getMessage());
                    return Mono.empty();
                })
                .defaultIfEmpty(new ServerStateDto().setServerId(serverId).setStatus(ServerStatus.PAUSED));
    }

    public Mono<byte[]> getImage(UUID serverId) {
        return gatewayService.of(serverId)//
                .flatMap(client -> client.getServer().getImage());
    }

    public Mono<Void> updatePlayer(UUID serverId, MindustryToolPlayerDto payload) {
        return gatewayService.of(serverId).flatMap(client -> client.getServer().updatePlayer(payload));
    }

    private Mono<Void> checkRunningServer(ServerConfig config, boolean shouldAutoTurnOff) {
        var serverId = config.getId();
        var flag = serverFlags.computeIfAbsent(serverId, (_ignore) -> EnumSet.noneOf(ServerFlag.class));

        if (config.isAutoTurnOff() == false) {
            // TODO: Restart when detect mismatch
            return Mono.empty();
        }

        return gatewayService.of(serverId)//
                .flatMap(client -> client.getServer().getState())//
                .defaultIfEmpty(new ServerStateDto().setServerId(serverId).setStatus(ServerStatus.NOT_RESPONSE))
                .flatMap(state -> {
                    boolean shouldKill = state.getPlayers().isEmpty();

                    if (shouldKill && shouldAutoTurnOff) {
                        if (flag != null && flag.contains(ServerFlag.KILL)) {
                            var event = LogEvent.info(serverId, "Auto shut down server");
                            eventBus.fire(event);
                            log.info(event.toString());
                            flag.remove(ServerFlag.KILL);
                            return remove(serverId);
                        } else {
                            flag.add(ServerFlag.KILL);
                            var event = LogEvent.info(serverId, "Server has no players, flag to kill");
                            eventBus.fire(event);
                            log.info(event.toString());
                            return Mono.empty();
                        }
                    } else {
                        if (flag.contains(ServerFlag.KILL)) {
                            var event = LogEvent.info(serverId, "Remove kill flag from server");
                            eventBus.fire(event);
                            log.info(event.toString());
                            flag.remove(ServerFlag.KILL);
                            return Mono.empty();
                        }
                    }

                    return Mono.empty();
                })//
                .retry(2)//
                .onErrorResume(error -> {
                    var event = LogEvent.error(serverId, "Error: " + error.getMessage());
                    eventBus.fire(event);
                    log.error(event.toString());

                    return Mono.empty();
                });
    }
}
