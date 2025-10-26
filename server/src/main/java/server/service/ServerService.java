package server.service;

import java.time.Duration;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedList;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import arc.files.Fi;
import arc.util.Log;
import dto.MapDto;
import dto.ModDto;
import dto.ServerStateDto;
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
import dto.ServerFileDto;
import server.manager.NodeManager;

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

    private final ConcurrentHashMap<UUID, EnumSet<ServerFlag>> serverFlags = new ConcurrentHashMap<>();
    private final LinkedList<FluxSink<BaseEvent>> eventSinks = new LinkedList<>();

    private static enum ServerFlag {
        KILL,
        RESTART
    }

    @PostConstruct
    private void registerEventHandler() {
        gatewayService.onEvent(event -> {
            Log.info("Gateway event published: " + event);

            eventSinks.forEach(sink -> {
                if (!sink.isCancelled())
                    sink.next(event);
            });
        });

        nodeManager.onEvent(event -> {
            Log.info("Manager event published: " + event);

            eventSinks.forEach(sink -> {
                if (!sink.isCancelled())
                    sink.next(event);
            });
        });
    }

    public Flux<BaseEvent> getEvents() {
        Flux<LogEvent> hearbeat = Flux.interval(Duration.ofMinutes(1))
                .map(index -> LogEvent.info(UUID.randomUUID(), "Server heartbeat"));

        Flux<BaseEvent> eventSink = Flux.create(emitter -> {
            emitter.onDispose(() -> {
                eventSinks.remove(emitter);
                Log.info("Client disconnected: " + eventSinks.size());
            });

            Log.info("Client connected: " + eventSinks.size());

            eventSinks.add(emitter);
        });

        return Flux.merge(hearbeat, eventSink);
    }

    @Scheduled(fixedDelay = 5, timeUnit = TimeUnit.SECONDS)
    private void scanServer() {
        nodeManager.list()
                .filter(state -> state.meta.isPresent())
                .map(state -> gatewayService.of(state.meta.get().getConfig().getId()))
                .subscribeOn(Schedulers.boundedElastic())
                .blockLast();
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
                .timeout(Duration.ofMinutes(5))
                .subscribeOn(Schedulers.boundedElastic())
                .blockLast();
    }

    public Mono<Void> remove(UUID serverId) {
        return nodeManager.remove(serverId);
    }

    public Mono<Boolean> pause(UUID serverId) {
        return gatewayService.of(serverId)//
                .getServer()//
                .pause();
    }

    public Flux<LogEvent> host(ServerConfig request) {
        var serverId = request.getId();
        var serverGateway = gatewayService.of(serverId).getServer();

        Flux<LogEvent> createFlux = nodeManager.create(request);
        Flux<LogEvent> waitingOkFlux = Flux.concat(//
                Mono.just(LogEvent.info(serverId, "Waiting for server to start")), //
                serverGateway//
                        .ok()
                        .then(Mono.just(LogEvent.info(serverId, "Server started, waiting for hosting"))));

        Flux<LogEvent> hostFlux = serverGateway.isHosting().flatMapMany(isHosting -> {

            if (isHosting) {
                var event = LogEvent.info(serverId, "Server is hosting, do nothing");
                Log.info("Server is hosting, do nothing");
                return Flux.just(event);
            }

            String[] preHostCommand = { //
                    "config name %s".formatted(request.getName()), //
                    "config desc %s".formatted(request.getDescription()), //
                    "version"
            };

            Flux<LogEvent> sendCommandFlux = Flux.concat(
                    Mono.just(LogEvent.info(serverId, "Config server: " + Arrays.toString(preHostCommand))),
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
                            .retryWhen(Retry.fixedDelay(50, Duration.ofMillis(100)))
                            .thenReturn(LogEvent.info(serverId, "Server hosting")));

            return Flux.concat(sendCommandFlux, sendHostFlux, waitForStatusFlux);
        });

        Flux<LogEvent> logFlux = Flux.create(emittor -> {
            Runnable cancelManager = nodeManager.onEvent(event -> {
                if (event instanceof LogEvent logEvent) {
                    emittor.next(logEvent);
                }
            });

            Runnable cancelGateway = gatewayService.onEvent(event -> {
                if (event instanceof LogEvent logEvent) {
                    emittor.next(logEvent);
                }
            });

            emittor.onDispose(() -> {
                cancelManager.run();
                cancelGateway.run();
            });

            hostFlux.doFinally(_sig -> emittor.complete());
        });

        return Flux.merge(Flux.concat(//
                createFlux,
                waitingOkFlux, //
                hostFlux//
        ), logFlux);
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

    public Flux<ServerFileDto> getFiles(UUID serverId, String path) {
        return nodeManager.getFiles(serverId, path);
    }

    public Mono<Boolean> isFileExists(UUID serverId, String path) {
        return nodeManager.getFile(serverId, path).map(Fi::exists);
    }

    public Mono<Void> writeFile(UUID serverId, String path, FilePart filePart) {
        return Utils.readAllBytes(filePart).flatMap(bytes -> nodeManager.writeFile(serverId, path, bytes));
    }

    public Mono<Boolean> deleteFile(UUID serverId, String path) {
        return nodeManager.deleteFile(serverId, path);
    }

    public Mono<Void> ok(UUID serverId) {
        return gatewayService.of(serverId).getServer().ok();
    }

    public Flux<NodeUsage> getUsage(UUID serverId) {
        return nodeManager.getNodeUsage(serverId);
    }

    public Mono<ServerStateDto> state(UUID serverId) {
        return gatewayService.of(serverId)//
                .getServer()//
                .getState()//
                .onErrorResume(error -> {
                    Log.err(error.getMessage());
                    return Mono.empty();
                })
                .defaultIfEmpty(new ServerStateDto().setServerId(serverId).setStatus("NOT_RESPONSE"));
    }

    public Mono<byte[]> getImage(UUID serverId) {
        return gatewayService.of(serverId)//
                .getServer()//
                .getImage();
    }

    public Mono<Void> updatePlayer(UUID serverId, MindustryToolPlayerDto payload) {
        return gatewayService.of(serverId).getServer().updatePlayer(payload);
    }

    private Mono<Void> checkRunningServer(ServerConfig config, boolean shouldAutoTurnOff) {
        var serverId = config.getId();
        var flag = serverFlags.computeIfAbsent(serverId, (_ignore) -> EnumSet.noneOf(ServerFlag.class));

        if (config.isAutoTurnOff() == false) {
            // TODO: Restart when detect mismatch
            return Mono.empty();
        }

        return gatewayService.of(serverId)//
                .getServer()//
                .getState()
                .flatMap(state -> {
                    boolean shouldKill = state.getPlayers().isEmpty();

                    if (shouldKill && shouldAutoTurnOff) {
                        if (flag != null && flag.contains(ServerFlag.KILL)) {
                            var event = LogEvent.info(serverId, "Auto shut down server");
                            nodeManager.fire(event);
                            log.info(event.toString());
                            flag.remove(ServerFlag.KILL);
                            return remove(serverId);
                        } else {
                            flag.add(ServerFlag.KILL);
                            var event = LogEvent.info(serverId, "Server has no players, flag to kill");
                            nodeManager.fire(event);
                            log.info(event.toString());
                            return Mono.empty();
                        }
                    } else {
                        if (flag.contains(ServerFlag.KILL)) {
                            var event = LogEvent.info(serverId, "Remove kill flag from server");
                            nodeManager.fire(event);
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
                    nodeManager.fire(event);
                    log.error(event.toString());

                    return Mono.empty();
                });
    }
}
