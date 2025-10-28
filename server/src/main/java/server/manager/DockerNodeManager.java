package server.manager;

import java.io.IOException;
import java.nio.channels.AsynchronousCloseException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import arc.files.Fi;
import arc.util.Log;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import server.EnvConfig;
import server.config.Const;
import server.service.EventBus;
import server.types.data.NodeUsage;
import server.types.data.ServerConfig;
import server.types.data.ServerMetadata;
import server.types.data.ServerState;
import server.types.data.ServerMisMatch;
import dto.ManagerMapDto;
import dto.ManagerModDto;
import dto.MapDto;
import dto.ModDto;
import dto.ServerFileDto;
import dto.ServerStateDto;
import events.LogEvent;
import events.StartEvent;
import events.StopEvent;
import server.utils.ApiError;
import server.utils.FileUtils;
import server.utils.Utils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;
import reactor.core.publisher.FluxSink.OverflowStrategy;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Event;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.LogConfig;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.PullResponseItem;
import com.github.dockerjava.api.model.RestartPolicy;
import com.github.dockerjava.api.model.Statistics;
import com.github.dockerjava.api.model.Volume;

@Service
@RequiredArgsConstructor
public class DockerNodeManager implements NodeManager {
    private final DockerClient dockerClient;
    private final EnvConfig envConfig;
    private final EventBus eventBus;

    private final Map<UUID, ResultCallback.Adapter<Frame>> logCallbacks = new ConcurrentHashMap<>();
    private ResultCallback.Adapter<Event> eventCallback = null;

    private static final Fi SERVER_FOLDER = new Fi(Const.volumeFolderPath).child("servers");

    static {
        SERVER_FOLDER.mkdirs();
    }

    @Override
    public Flux<LogEvent> create(ServerConfig request) {
        var serverId = request.getId();

        return Flux.create(emitter -> {
            try {
                emitter.next(LogEvent.info(serverId, "Pulling image: " + request.getImage()));

                try {
                    dockerClient.pullImageCmd(request.getImage())
                            .exec(new ResultCallback.Adapter<PullResponseItem>())
                            .awaitCompletion();
                } catch (NotFoundException ex) {
                    emitter.next(LogEvent.error(serverId, "Image not found: " + request.getImage()));
                    emitter.error(ex);
                    return;
                }

                emitter.next(LogEvent.info(serverId, "Image pulled"));

                String serverIdString = request.getId().toString();
                var serverPath = Paths.get(Const.volumeFolderPath, "servers", serverIdString, "config")
                        .toAbsolutePath();

                try {
                    Files.createDirectories(serverPath);
                } catch (Exception e) {
                    emitter.next(LogEvent.error(serverId, e.getMessage()));
                }

                var servers = dockerClient.listContainersCmd()
                        .withShowAll(true)
                        .withLabelFilter(List.of(Const.serverLabelName))
                        .exec();

                for (var server : servers) {
                    var optional = readMetadataFromContainer(server);

                    if (optional.isEmpty()) {
                        continue;
                    }

                    var config = optional.get().getConfig();
                    var isSamePort = config.getPort() == request.getPort();
                    var isSameId = config.getId().equals(request.getId());

                    if (isSamePort && !isSameId) {
                        emitter.next(LogEvent.error(serverId,
                                "Port exists at conatiner " + server.getNames()[0] + " port: " + config.getPort()));

                        emitter.error(new ApiError(HttpStatus.BAD_REQUEST,
                                "Port exists at conatiner " + server.getNames()[0] + " port: " + config.getPort()));
                        return;
                    }
                }

                var containers = dockerClient.listContainersCmd()//
                        .withShowAll(true)//
                        .withLabelFilter(Map.of(Const.serverIdLabel, request.getId().toString()))//
                        .exec();

                for (var container : containers) {
                    emitter.next(LogEvent.info(serverId, "Removing container " + container.getNames()[0]));
                    dockerClient.removeContainerCmd(container.getId())
                            .withForce(true)
                            .exec();
                }

                Volume volume = new Volume("/config");
                Bind bind = new Bind(serverPath.toString(), volume);

                ExposedPort tcp = ExposedPort.tcp(Const.DEFAULT_MINDUSTRY_SERVER_PORT);
                ExposedPort udp = ExposedPort.udp(Const.DEFAULT_MINDUSTRY_SERVER_PORT);

                Ports portBindings = new Ports();

                portBindings.bind(tcp, Ports.Binding.bindPort(request.getPort()));
                portBindings.bind(udp, Ports.Binding.bindPort(request.getPort()));

                emitter.next(LogEvent.info(serverId, "Creating new container on port " + request.getPort()));

                var image = request.getImage() == null || request.getImage().isEmpty()
                        ? envConfig.docker().mindustryServerImage()
                        : request.getImage();

                var serverImage = dockerClient.inspectImageCmd(request.getImage()).exec();

                var currentMetadata = new ServerMetadata()
                        .setConfig(request)
                        .setServerImageHash(serverImage.getId());

                var command = dockerClient.createContainerCmd(image)//
                        .withName(request.getId().toString())//
                        .withLabels(Map.of(//
                                Const.serverLabelName, Utils.toJsonString(currentMetadata),
                                Const.serverIdLabel, request.getId().toString()//
                ));

                var env = new ArrayList<String>();
                var exposedPorts = new ArrayList<ExposedPort>();

                exposedPorts.add(tcp);
                exposedPorts.add(udp);

                List<String> args = List.of(
                        "-XX:+UseContainerSupport",
                        "-XX:MaxRAMPercentage=75.0",
                        "-XX:+CrashOnOutOfMemoryError",
                        "-XX:+UseSerialGC",
                        "-XX:+AlwaysPreTouch",
                        "-XX:MaxHeapFreeRatio=20",
                        "-XX:MinHeapFreeRatio=10",
                        "-XX:GCTimeRatio=99",
                        "-XX:MaxRAM=" + request.getPlan().getRam() + "m");

                env.addAll(request.getEnv().entrySet().stream().map(v -> v.getKey() + "=" + v.getValue()).toList());
                env.add("IS_HUB=" + request.isHub());
                env.add("SERVER_ID=" + serverId);
                env.add("JAVA_TOOL_OPTIONS=" + String.join(" ", args));

                if (Const.IS_DEVELOPMENT) {
                    env.add("ENV=DEV");
                    ExposedPort localTcp = ExposedPort.tcp(9999);
                    portBindings.bind(localTcp, Ports.Binding.bindPort(9999));
                    exposedPorts.add(localTcp);
                }

                command.withExposedPorts(exposedPorts)//
                        .withEnv(env)
                        .withHostConfig(HostConfig.newHostConfig()//
                                .withPortBindings(portBindings)//
                                .withNetworkMode("mindustry-server")//
                                // in bytes
                                .withCpuPeriod(100000l)
                                .withCpuQuota((long) ((request.getPlan().getCpu() * 100000)))
                                .withRestartPolicy(request.isAutoTurnOff()//
                                        ? RestartPolicy.noRestart()
                                        : RestartPolicy.unlessStoppedRestart())
                                .withAutoRemove(request.isAutoTurnOff())
                                .withLogConfig(new LogConfig(LogConfig.LoggingType.JSON_FILE, Map.of(
                                        "max-size", "100m",
                                        "max-file", "5"//
                )))
                                .withBinds(bind));

                var result = command.exec();

                var containerId = result.getId();

                dockerClient.startContainerCmd(containerId).exec();

                logCallbacks.computeIfAbsent(serverId, _ignore -> createLogCallack(containerId, serverId));

                emitter.next(LogEvent.info(serverId, "Container " + containerId + " started"));
            } catch (Exception error) {
                emitter.next(LogEvent.error(serverId, "Error: " + error.getMessage()));
            } finally {
                emitter.complete();
            }
        }, OverflowStrategy.BUFFER);
    }

    @Override
    public Flux<ServerState> list() {
        var containers = dockerClient.listContainersCmd()//
                .withShowAll(true)//
                .withLabelFilter(List.of(Const.serverLabelName))//
                .exec();

        return Flux.fromIterable(containers)
                .map(container -> new ServerState()//
                        .running(container.getState().equals("running"))//
                        .meta(readMetadataFromContainer(container)));
    }

    @Override
    public Mono<Void> remove(UUID id) {
        var optional = findContainerByServerId(id);

        if (optional.isEmpty()) {
            return ApiError.notFound(id, "Server not found");
        }

        var container = optional.get();

        if (!container.getState().equalsIgnoreCase("stopped")) {
            dockerClient.stopContainerCmd(container.getId()).exec();
            Log.info("Stopped: " + container.getNames()[0]);
        }

        dockerClient.removeContainerCmd(container.getId())
                .withForce(true)
                .exec();

        Log.info("Removed: " + container.getNames()[0]);

        return Mono.empty();
    }

    private Optional<ServerMetadata> readMetadataFromContainer(Container container) {
        try {
            var label = container.getLabels().get(Const.serverLabelName);

            if (label == null) {
                return Optional.empty();
            }

            var metadata = Utils.readJsonAsClass(label, ServerMetadata.class);

            if (metadata == null || metadata.getConfig() == null) {
                dockerClient.removeContainerCmd(container.getId())
                        .withForce(true)
                        .exec();

                return Optional.empty();
            }

            return Optional.of(metadata);
        } catch (Exception _e) {
            dockerClient.removeContainerCmd(container.getId())
                    .withForce(true)
                    .exec();

            return Optional.empty();
        }
    }

    private Optional<Container> findContainerByServerId(UUID serverId) {
        var containers = dockerClient.listContainersCmd()//
                .withLabelFilter(Map.of(Const.serverIdLabel, serverId.toString()))//
                .withShowAll(true)//
                .exec();

        if (containers.isEmpty()) {
            return Optional.empty();
        } else if (containers.size() == 1) {
            return Optional.ofNullable(containers.get(0));
        }

        return Optional.ofNullable(containers.get(0));
    }

    @Override
    public Flux<ServerMisMatch> getMismatch(UUID id, ServerConfig config, ServerStateDto state, List<ModDto> mods) {
        var optional = findContainerByServerId(id);

        if (optional.isEmpty()) {
            return Flux.error(new IllegalArgumentException("Server not found"));
        }

        var container = optional.get();

        var meta = readMetadataFromContainer(container).orElseThrow();

        List<ServerMisMatch> result = ServerMisMatch.from(meta, config, state, mods);

        var serverImage = dockerClient.inspectImageCmd(meta.getConfig().getImage()).exec();

        if (!meta.getServerImageHash().equals(serverImage.getId())) {
            result.add(new ServerMisMatch("serverImage", serverImage.getId(), meta.getServerImageHash()));
        }

        return Flux.fromIterable(result);
    }

    private List<Container> listContainers() {
        return dockerClient.listContainersCmd()//
                .withShowAll(true)//
                .withLabelFilter(List.of(Const.serverLabelName))//
                .exec();
    }

    @PostConstruct
    @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.MINUTES)
    private void findAndAttachToLogs() {
        var containers = listContainers();

        for (var container : containers) {
            var optional = readMetadataFromContainer(container);
            try {
                if (optional.isPresent()) {
                    var metadata = optional.orElseThrow();
                    var serverId = metadata.getConfig().getId();

                    logCallbacks.computeIfAbsent(serverId, _ignore -> createLogCallack(container.getId(), serverId));
                }
            } catch (Exception e) {
                Log.err(e.getMessage());
            }
        }
    }

    @PreDestroy
    private void close() {
        logCallbacks.values().forEach(callback -> {
            try {
                callback.close();
            } catch (IOException _e) {
                // Ignore
            }
        });

        if (eventCallback != null) {
            try {
                eventCallback.close();
            } catch (IOException _e) {
                // Ignore
            }
        }
    }

    @PostConstruct
    public void init() {
        final Set<String> ignoredEvents = Set.of("exec_create", "exec_start", "exec_die", "exec_detach");

        eventCallback = dockerClient.eventsCmd()
                .exec(new ResultCallback.Adapter<>() {
                    @Override
                    public void onNext(Event event) {
                        String containerId = event.getId();
                        String action = event.getAction();
                        String status = event.getStatus();

                        if (status == null) {
                            return;
                        }

                        if (ignoredEvents.stream().anyMatch(ignored -> status.toLowerCase().startsWith(ignored))) {
                            return;
                        }

                        var containers = dockerClient.listContainersCmd()
                                .withIdFilter(List.of(containerId))
                                .exec();

                        if (containers.size() != 1) {
                            return;
                        }

                        var container = containers.get(0);

                        var optional = readMetadataFromContainer(container);

                        optional.ifPresent(metadata -> {
                            var serverId = metadata.getConfig().getId();
                            var stopEvents = List.of("stop", "die", "kill", "destroy");

                            if (status.equalsIgnoreCase("start")) {

                                logCallbacks.computeIfAbsent(
                                        metadata.getConfig().getId(),
                                        id -> createLogCallack(containerId, id));

                                eventBus.fire(new StartEvent(serverId));
                            } else if (stopEvents.stream().anyMatch(stop -> status.equalsIgnoreCase(stop))) {
                                eventBus.fire(new StopEvent(serverId));
                            }
                        });

                        String name = optional
                                .map(meta -> meta.getConfig().getName())
                                .or(() -> Optional.ofNullable(event.getFrom()))
                                .orElse(event.getId());

                        String message = "Server %s %s %s".formatted(name, event.getStatus(), action);

                        Log.info(message);
                    }
                });
    }

    private synchronized ResultCallback.Adapter<Frame> createLogCallack(String containerId, UUID serverId) {
        var callback = new ResultCallback.Adapter<Frame>() {
            @Override
            public void onNext(Frame frame) {
                var message = new String(frame.getPayload());
                if (message.isBlank()) {
                    return;
                }

                eventBus.fire(LogEvent.info(serverId, message));
            }

            @Override
            public void onComplete() {
                Log.info("[" + serverId + "] Log stream ended.");

                logCallbacks.remove(serverId);
            }

            @Override
            public void onError(Throwable throwable) {
                logCallbacks.remove(serverId);

                if (!(throwable instanceof AsynchronousCloseException)) {
                    System.err
                            .println("[" + serverId + "] Log stream error: " + throwable.getMessage());
                    throwable.printStackTrace();
                }
            }
        };

        dockerClient.logContainerCmd(containerId)
                .withStdOut(true)
                .withStdErr(true)
                .withFollowStream(true)
                .withTail(0)
                .exec(callback);

        Log.info("[" + serverId + "] Log stream attached.");

        return callback;
    }

    public Flux<NodeUsage> getNodeUsage(UUID serverId) {
        var optional = findContainerByServerId(serverId);

        if (optional.isEmpty()) {
            return Flux.error(new IllegalArgumentException("Server not found"));
        }

        var container = optional.get();
        var containerId = container.getId();

        return Flux.create(emitter -> {

            var callback = new ResultCallback.Adapter<Statistics>() {
                Statistics lastStats = null;

                @Override
                public void onNext(Statistics currentStats) {
                    if (lastStats == null) {
                        lastStats = currentStats;
                        return;
                    }

                    float cpu = 0f;

                    Long cpuDelta = currentStats.getCpuStats().getCpuUsage().getTotalUsage()
                            - lastStats.getCpuStats().getCpuUsage().getTotalUsage();

                    Long systemDelta = Optional.ofNullable(currentStats.getCpuStats().getSystemCpuUsage())
                            .orElse(0L)
                            - Optional.ofNullable(lastStats.getCpuStats().getSystemCpuUsage()).orElse(0L);

                    Long cpuCores = currentStats.getCpuStats().getOnlineCpus();

                    if (systemDelta != null && systemDelta > 0 && cpuCores != null && cpuCores > 0) {
                        cpu = ((float) cpuDelta / systemDelta * cpuCores * 100.0f);
                    }

                    long ram = Optional.ofNullable(currentStats.getMemoryStats().getUsage()).orElse(0L); // bytes

                    emitter.next(new NodeUsage(cpu, ram, Instant.now()));
                }

                @Override
                public void onComplete() {
                    Log.info("[" + serverId + "] Stats stream ended.");

                    if (emitter.isCancelled() == false) {
                        emitter.complete();
                    }
                }

                @Override
                public void onError(Throwable throwable) {
                    Log.info("[" + serverId + "] Stats stream error: " + throwable.getMessage());

                    if (emitter.isCancelled() == false) {
                        emitter.error(throwable);
                    }
                }
            };

            if (emitter.isCancelled() == false) {
                emitter.onDispose(() -> {
                    try {
                        callback.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                });

                dockerClient.statsCmd(containerId)
                        .exec(callback);
            }
        });
    }

    @Override
    public Flux<ManagerMapDto> getManagerMaps() {
        var result = new HashMap<String, Tuple2<Fi, List<UUID>>>();

        for (var serverFolder : SERVER_FOLDER.list()) {
            var mapFolder = serverFolder.child("config").child("maps");

            if (!mapFolder.exists()) {
                continue;
            }

            for (var mapFile : mapFolder.findAll(Utils::isMapFile)) {
                result.computeIfAbsent(mapFile.name(), (_ignore) -> Tuples.of(mapFile, new ArrayList<>()))
                        .getT2()
                        .add(UUID.fromString(serverFolder.name()));
            }
        }

        return Flux.fromIterable(result.values()).map(entry -> {
            var map = Utils.loadMap(entry.getT1());
            var servers = entry.getT2();

            return new ManagerMapDto()//
                    .setServers(servers)
                    .setMetadata(map);
        });

    }

    @Override
    public Flux<ManagerModDto> getManagerMods() {
        var result = new HashMap<String, Tuple2<Fi, List<UUID>>>();

        for (var serverFolder : SERVER_FOLDER.list()) {
            var modFolder = serverFolder
                    .child("config")
                    .child("mods");

            if (!modFolder.exists()) {
                continue;
            }

            for (var mapFile : modFolder.findAll(Utils::isModFile)) {
                result.computeIfAbsent(mapFile.name(), (_ignore) -> Tuples.of(mapFile, new ArrayList<>()))
                        .getT2()
                        .add(UUID.fromString(serverFolder.name()));
            }
        }

        return Flux.fromIterable(result.values()).flatMap(entry -> {
            var meta = Utils.loadMod(entry.getT1());

            return Mono.just(new ManagerModDto()//
                    .setData(meta)//
                    .setServers(entry.getT2()));
        });
    }

    @Override
    public Flux<MapDto> getMaps(UUID serverId) {
        return getFile(serverId, "maps")
                .filter(folder -> folder.exists())
                .flatMapIterable(folder -> folder.findAll().map(Utils::loadMap));
    }

    @Override
    public Flux<ModDto> getMods(UUID serverId) {
        return getFile(serverId, "mods")
                .filter(folder -> folder.exists())
                .flatMapIterable(folder -> folder.findAll(Utils::isModFile).map(Utils::loadMod));
    }

    @Override
    public Mono<Fi> getFile(UUID serverId, String path) {
        if (path.contains("..") || path.contains("./")) {
            return Mono.error(new IllegalArgumentException("Invalid path: " + path));
        }

        var basePath = Paths.get(Const.volumeFolderPath, "servers", serverId.toString(), "config")
                .toAbsolutePath().toString();

        return Mono.just(new Fi(basePath).child(path));

    }

    @Override
    public Mono<Boolean> deleteFile(UUID serverId, String path) {
        return getFile(serverId, path)
                .map(FileUtils::deleteFile);
    }

    @Override
    public Flux<ServerFileDto> getFiles(UUID serverId, String path) {
        return getFile(serverId, path)
                .flatMapIterable(folder -> FileUtils.getFiles(folder.absolutePath()));
    }

    @Override
    public Mono<Void> writeFile(UUID serverId, String path, byte[] data) {
        return getFile(serverId, path)
                .doOnNext(file -> FileUtils.writeFile(file.absolutePath(), data))
                .then();
    }
}
