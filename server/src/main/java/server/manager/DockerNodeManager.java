package server.manager;

import java.io.Closeable;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import arc.files.Fi;
import arc.util.Log;
import server.EnvConfig;
import server.config.Const;
import server.service.EventBus;
import server.types.data.NodeUsage;
import server.types.data.ServerState;
import server.types.data.ServerMisMatch;
import dto.ManagerMapDto;
import dto.ManagerModDto;
import dto.MapDto;
import dto.ModDto;
import dto.ServerConfig;
import dto.ServerMetadata;
import dto.ServerStateDto;
import events.ServerEvents;
import events.ServerEvents.LogEvent;
import enums.NodeRemoveReason;
import server.utils.ApiError;
import server.utils.FileUtils;
import server.utils.Utils;

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

public class DockerNodeManager implements NodeManager {
    private final DockerClient dockerClient;
    private final EnvConfig envConfig;
    private final EventBus eventBus;

    private final Map<UUID, ResultCallback.Adapter<Frame>> logCallbacks = new ConcurrentHashMap<>();

    private static final Fi SERVER_FOLDER = new Fi(Const.volumeFolderPath).child("servers");

    public DockerNodeManager(DockerClient dockerClient, EnvConfig envConfig, EventBus eventBus) {
        this.dockerClient = dockerClient;
        this.envConfig = envConfig;
        this.eventBus = eventBus;
        init();
    }

    @Override
    public void create(ServerConfig request) {
        UUID serverId = request.getId();
        try {
            var containers = dockerClient.listContainersCmd()
                    .withShowAll(true)
                    .withLabelFilter(Map.of(Const.serverIdLabel, request.getId().toString()))
                    .exec();

            for (var container : containers) {
                Log.info("Removing container " + container.getNames()[0]);
                removeContainer(container.getId());
                Log.info("Container removed");
            }

            Log.info("Pulling image: " + request.getImage());
            try {
                dockerClient.pullImageCmd(request.getImage())
                        .exec(new ResultCallback.Adapter<PullResponseItem>())
                        .awaitCompletion();
            } catch (NotFoundException ex) {
                Log.err("Image not found: " + request.getImage());
                return;
            } catch (Exception ex) {
                Log.err("Error: " + ex.getMessage());
            }

            Log.info("Image pulled");

            String serverIdString = request.getId().toString();
            var serverPath = Paths.get(Const.volumeFolderPath, "servers", serverIdString, "config")
                    .toAbsolutePath();

            try {
                Files.createDirectories(serverPath);
                Log.info("Server folder created: " + serverPath);
            } catch (Exception e) {
                Log.err("Error: " + e.getMessage());
            }

            var servers = dockerClient.listContainersCmd()
                    .withShowAll(true)
                    .withLabelFilter(List.of(Const.serverLabelName))
                    .exec();

            for (var server : servers) {
                var optional = readMetadataFromContainer(server);
                if (optional.isEmpty())
                    continue;

                var config = optional.get().getConfig();
                var isSamePort = config.getPort() == request.getPort();
                var isSameId = config.getId().equals(request.getId());

                if (isSamePort && !isSameId) {
                    Log.err("Port exists at container " + server.getNames()[0] + " port: " + config.getPort());
                    return;
                }
            }

            Volume volume = new Volume("/config");
            Bind bind = new Bind(serverPath.toString(), volume);

            ExposedPort tcp = ExposedPort.tcp(Const.DEFAULT_MINDUSTRY_SERVER_PORT);
            ExposedPort udp = ExposedPort.udp(Const.DEFAULT_MINDUSTRY_SERVER_PORT);

            Ports portBindings = new Ports();
            portBindings.bind(tcp, Ports.Binding.bindPort(request.getPort()));
            portBindings.bind(udp, Ports.Binding.bindPort(request.getPort()));

            Log.info("Creating new container on port " + request.getPort());

            var image = request.getImage() == null || request.getImage().isEmpty()
                    ? envConfig.docker().mindustryServerImage()
                    : request.getImage();

            var serverImage = dockerClient.inspectImageCmd(image).exec();

            var currentMetadata = new ServerMetadata()
                    .setConfig(request)
                    .setServerImageHash(serverImage.getId());

            var command = dockerClient.createContainerCmd(image)
                    .withName(request.getId().toString())
                    .withLabels(Map.of(
                            Const.serverLabelName, Utils.toJsonString(currentMetadata),
                            Const.serverIdLabel, request.getId().toString()));

            var env = new ArrayList<String>();
            var exposedPorts = new ArrayList<ExposedPort>();
            exposedPorts.add(tcp);
            exposedPorts.add(udp);

            List<String> args = List.of(
                    "-XX:+CrashOnOutOfMemoryError",
                    "-XX:MaxRAM=" + request.getMemory() + "m", "-XX:+HeapDumpOnOutOfMemoryError",
                    "-XX:HeapDumpPath=/config",
                    "-XX:MinHeapFreeRatio=5",
                    "-XX:MaxHeapFreeRatio=20",
                    "-XX:MaxDirectMemorySize=128m",
                    "-XX:-ShrinkHeapInSteps",
                    "-XX:MaxRAMPercentage=60");

            env.add("IS_HUB=" + request.getIsHub());
            env.add("IS_OFFICIAL=" + request.getIsOfficial());
            env.add("SERVER_ID=" + serverId);
            env.add("JAVA_TOOL_OPTIONS=" + String.join(" ", args));
            env.addAll(request.getEnv().entrySet().stream().map(v -> v.getKey() + "=" + v.getValue()).toList());

            if (Const.IS_DEVELOPMENT) {
                env.add("ENV=DEV");
                ExposedPort localTcp = ExposedPort.tcp(9999);
                portBindings.bind(localTcp, Ports.Binding.bindPort(9999));
                exposedPorts.add(localTcp);
            }

            command.withExposedPorts(exposedPorts)
                    .withEnv(env)
                    .withHostConfig(HostConfig.newHostConfig()
                            .withPortBindings(portBindings)
                            .withNetworkMode("mindustry-server")
                            .withCpuPeriod(100000L)
                            .withCpuQuota((long) (request.getCpu() * 100000L))
                            .withMemory(request.getMemory() * 1024 * 1024L)
                            .withRestartPolicy(request.getIsAutoTurnOff()
                                    ? RestartPolicy.noRestart()
                                    : RestartPolicy.unlessStoppedRestart())
                            .withLogConfig(new LogConfig(LogConfig.LoggingType.JSON_FILE, Map.of(
                                    "max-size", "100m",
                                    "max-file", "5")))
                            .withPidsLimit(64L)
                            .withDns("8.8.8.8", "1.1.1.1")
                            .withExtraHosts("server.mindustry-tool.com:15.235.147.240",
                                    "api.mindustry-tool.com:15.235.147.240")
                            .withRuntime("io.containerd.kata.v2")
                            .withBinds(bind));

            var result = command.exec();
            var containerId = result.getId();

            dockerClient.startContainerCmd(containerId).exec();
            attachLogCallback(containerId, serverId);

            Log.info("Container " + containerId + " started");
        } catch (Exception e) {
            Log.err("Error: " + e.getMessage());
        }
    }

    @Override
    public List<ServerState> list() {
        var containers = dockerClient.listContainersCmd()
                .withShowAll(true)
                .withLabelFilter(List.of(Const.serverLabelName))
                .exec();

        List<ServerState> states = new ArrayList<>();
        for (var container : containers) {
            states.add(new ServerState()
                    .running(container.getState().equalsIgnoreCase("running"))
                    .meta(readMetadataFromContainer(container)));
        }
        return states;
    }

    @Override
    public CompletableFuture<Void> remove(UUID id, NodeRemoveReason reason) {
        return CompletableFuture.runAsync(() -> {
            var optional = findContainerByServerId(id);
            if (optional.isEmpty()) {
                throw new ApiError(404, "Server not found");
            }

            var container = optional.get();
            if (container.getState().equalsIgnoreCase("running")) {
                dockerClient.stopContainerCmd(container.getId()).exec();
            }

            removeContainer(container.getId());
            eventBus.emit(
                    ServerEvents.LogEvent.error(id, "Removed: " + container.getNames()[0] + " for reason: " + reason));
        });
    }

    private synchronized void removeContainer(String id) {
        dockerClient.removeContainerCmd(id)
                .withForce(true)
                .exec();
    }

    private Optional<ServerMetadata> readMetadataFromContainer(Container container) {
        try {
            var label = container.getLabels().get(Const.serverLabelName);
            if (label == null)
                return Optional.empty();

            var metadata = Utils.readJsonAsClass(label, ServerMetadata.class);
            if (metadata == null || metadata.getConfig() == null) {
                throw new IllegalArgumentException("Invalid config: " + label);
            }
            return Optional.of(metadata);
        } catch (Exception e) {
            removeContainer(container.getId());
            Log.err(e);
            return Optional.empty();
        }
    }

    private Optional<Container> findContainerByServerId(UUID serverId) {
        var containers = dockerClient.listContainersCmd()
                .withLabelFilter(Map.of(Const.serverIdLabel, serverId.toString()))
                .withShowAll(true)
                .exec();

        if (containers.isEmpty())
            return Optional.empty();
        return Optional.of(containers.get(0));
    }

    @Override
    public List<ServerMisMatch> getMismatch(UUID id, ServerConfig config, ServerStateDto state, List<ModDto> mods) {
        var optional = findContainerByServerId(id);
        if (optional.isEmpty()) {
            throw new IllegalArgumentException("Server not found");
        }

        var container = optional.get();
        var meta = readMetadataFromContainer(container).orElseThrow();
        List<ServerMisMatch> result = ServerMisMatch.from(meta, config, state, mods);

        var serverImage = dockerClient.inspectImageCmd(meta.getConfig().getImage()).exec();
        if (!meta.getServerImageHash().equals(serverImage.getId())) {
            result.add(new ServerMisMatch()
                    .setField("Server image mismatch")
                    .setExpected(serverImage.getId())
                    .setCurrent(meta.getServerImageHash()));
        }

        return result;
    }

    @Override
    public void getNodeUsage(UUID serverId, Consumer<NodeUsage> onUsage, Consumer<Throwable> onError) {
        var optional = findContainerByServerId(serverId);

        if (optional.isEmpty()) {
            onError.accept(new IllegalArgumentException("Server not found"));
            return;
        }

        var containerId = optional.get().getId();

        dockerClient.statsCmd(containerId).exec(new ResultCallback.Adapter<Statistics>() {
            Statistics lastStats = null;

            @Override
            public void onNext(Statistics currentStats) {
                double cpuPercent = calculateCpuPercent(lastStats, currentStats);
                long ram = Optional.ofNullable(currentStats.getMemoryStats()).map(stats -> stats.getUsage()).orElse(0L);
                lastStats = currentStats;

                onUsage.accept(new NodeUsage(cpuPercent, ram, Instant.now()));
            }

            @Override
            public void onError(Throwable throwable) {
                onError.accept(throwable);
            }
        });
    }

    private double calculateCpuPercent(Statistics lastStats, Statistics currentStats) {
        if (lastStats == null) {
            return 0;
        }

        double cpuPercent = 0.0;

        Long prevTotal = Optional.ofNullable(lastStats.getCpuStats())
                .map(s -> s.getCpuUsage())
                .map(u -> u.getTotalUsage())
                .orElse(0L);

        Long currTotal = Optional.ofNullable(currentStats.getCpuStats())
                .map(s -> s.getCpuUsage())
                .map(u -> u.getTotalUsage())
                .orElse(0L);

        Long prevSystem = Optional.ofNullable(lastStats.getCpuStats())
                .map(s -> s.getSystemCpuUsage())
                .orElse(0L);

        Long currSystem = Optional.ofNullable(currentStats.getCpuStats())
                .map(s -> s.getSystemCpuUsage())
                .orElse(0L);

        long cpuDelta = currTotal - prevTotal;
        long systemDelta = currSystem - prevSystem;

        Long onlineCpus = Optional.ofNullable(currentStats.getCpuStats())
                .map(s -> s.getOnlineCpus())
                .orElse(null);

        long cpuCount = 0;

        if (onlineCpus != null && onlineCpus > 0) {
            cpuCount = onlineCpus;
        } else {
            List<Long> perCpu = Optional.ofNullable(currentStats.getCpuStats())
                    .map(s -> s.getCpuUsage())
                    .map(u -> u.getPercpuUsage())
                    .orElse(null);
            if (perCpu != null && !perCpu.isEmpty()) {
                cpuCount = perCpu.size();
            } else {
                cpuCount = Runtime.getRuntime().availableProcessors();
            }
        }

        if (systemDelta > 0 && cpuDelta >= 0 && cpuCount > 0) {
            cpuPercent = ((double) cpuDelta / (double) systemDelta) * cpuCount * 100.0;
            if (cpuPercent < 0)
                cpuPercent = 0;
            if (cpuPercent > cpuCount * 100.0)
                cpuPercent = cpuCount * 100.0;
        } else {
            cpuPercent = 0.0;
        }

        return cpuPercent;
    }

    @Override
    public List<ManagerMapDto> getManagerMaps() {
        var result = new HashMap<String, List<UUID>>();
        for (var serverFolder : SERVER_FOLDER.list()) {
            var mapFolder = serverFolder.child("config").child("maps");
            if (!mapFolder.exists())
                continue;

            for (var mapFile : mapFolder.findAll(Utils::isMapFile)) {
                result.computeIfAbsent(mapFile.name(), k -> new ArrayList<>())
                        .add(UUID.fromString(serverFolder.name()));
            }
        }

        List<ManagerMapDto> maps = new ArrayList<>();
        for (var entry : result.entrySet()) {
            var map = Utils.loadMap(getBaseFile(entry.getValue().get(0)), SERVER_FOLDER
                    .child(entry.getValue().get(0).toString()).child("config").child("maps").child(entry.getKey()));
            maps.add(new ManagerMapDto().setServers(entry.getValue()).setMetadata(map));
        }
        return maps;
    }

    @Override
    public List<ManagerModDto> getManagerMods() {
        var result = new HashMap<String, List<UUID>>();
        for (var serverFolder : SERVER_FOLDER.list()) {
            var modFolder = serverFolder.child("config").child("mods");
            if (!modFolder.exists())
                continue;

            for (var modFile : modFolder.findAll(Utils::isModFile)) {
                result.computeIfAbsent(modFile.name(), k -> new ArrayList<>())
                        .add(UUID.fromString(serverFolder.name()));
            }
        }

        List<ManagerModDto> mods = new ArrayList<>();
        for (var entry : result.entrySet()) {
            var meta = Utils.loadMod(SERVER_FOLDER.child(entry.getValue().get(0).toString()).child("config")
                    .child("mods").child(entry.getKey()));
            mods.add(new ManagerModDto().setData(meta).setServers(entry.getValue()));
        }
        return mods;
    }

    @Override
    public List<MapDto> getMaps(UUID serverId) {
        Fi folder = getFile(serverId, "maps");
        if (!folder.exists())
            return List.of();
        List<MapDto> maps = new ArrayList<>();
        for (Fi file : folder.findAll(Utils::isMapFile)) {
            maps.add(Utils.loadMap(getBaseFile(serverId), file));
        }
        return maps;
    }

    @Override
    public List<ModDto> getMods(UUID serverId) {
        Fi folder = getFile(serverId, "mods");
        if (!folder.exists())
            return List.of();
        List<ModDto> mods = new ArrayList<>();
        for (Fi file : folder.findAll(Utils::isModFile)) {
            mods.add(Utils.loadMod(file));
        }
        return mods;
    }

    public Fi getBaseFile(UUID serverId) {
        var basePath = Paths.get(Const.volumeFolderPath, "servers", serverId.toString(), "config").toAbsolutePath()
                .toString();
        var file = new Fi(basePath);
        file.mkdirs();
        return file;
    }

    @Override
    public Fi getServerFolder() {
        return SERVER_FOLDER;
    }

    @Override
    public Fi getFile(UUID serverId, String path) {
        return FileUtils.getFile(getBaseFile(serverId), path);
    }

    @Override
    public Object getFiles(UUID serverId, String path) {
        Fi file = getFile(serverId, path);
        if (!file.exists())
            throw new ApiError(404, file.absolutePath());
        return FileUtils.getFiles(file.absolutePath());
    }

    @Override
    public void writeFile(UUID serverId, String path, byte[] data) {
        Fi file = getFile(serverId, path);
        FileUtils.writeFile(file.absolutePath(), data);
    }

    @Override
    public boolean createFolder(UUID serverId, String path) {
        return getFile(serverId, path).mkdirs();
    }

    @Override
    public boolean deleteFile(UUID serverId, String path) {
        return FileUtils.deleteFile(getFile(serverId, path));
    }

    private void init() {
        SERVER_FOLDER.mkdirs();

        final Set<String> ignoredEvents = Set.of("exec_create", "exec_start", "exec_die", "exec_detach");

        Log.info("Listen to docker event");

        Const.executorService.execute(() -> {
            dockerClient.eventsCmd().exec(new ResultCallback.Adapter<Event>() {
                @Override
                public void onNext(Event event) {
                    String containerId = event.getId();
                    String action = event.getAction();
                    String status = event.getStatus();

                    if (status == null) {
                        Log.warn("Docker event with null status: @", event);
                        return;
                    }

                    if (ignoredEvents.stream().anyMatch(ignored -> status.toLowerCase().startsWith(ignored))) {
                        return;
                    }

                    if (containerId == null) {
                        Log.warn("Docker event with null container id: @", event);
                        return;
                    }

                    var containers = dockerClient.listContainersCmd()
                            .withIdFilter(List.of(containerId))
                            .exec();

                    if (containers.size() != 1) {
                        Log.warn("Docker event with multiple containers: @", event);
                        return;
                    }

                    var container = containers.get(0);

                    var optional = readMetadataFromContainer(container);

                    optional.ifPresentOrElse(metadata -> {
                        var serverId = metadata.getConfig().getId();

                        if (status.equalsIgnoreCase("start")) {
                            attachLogCallback(containerId, serverId);
                        }
                    }, () -> {
                        var serverIdString = container.getLabels().get(Const.serverIdLabel);
                        if (serverIdString == null) {
                            return;
                        }

                        Log.info("Server %s %s %s".formatted(serverIdString, event.getStatus(), action));
                    });

                    String name = optional
                            .map(meta -> meta.getConfig().getName())
                            .or(() -> Optional.ofNullable(event.getFrom()))
                            .orElse(event.getId());

                    String message = "Server %s %s %s".formatted(name, event.getStatus(), action);

                    Log.info(message);
                }
            });
        });
    }

    private synchronized void attachLogCallback(String containerId, UUID serverId) {
        logCallbacks.computeIfAbsent(serverId, k -> {
            var callback = new ResultCallback.Adapter<Frame>() {
                @Override
                public void onStart(Closeable closeable) {
                    Log.info("Log callback attached for server %s", serverId);
                }

                @Override
                public void onNext(Frame frame) {
                    var message = new String(frame.getPayload());
                    if (!message.isBlank()) {
                        eventBus.emit(LogEvent.info(serverId, message));
                    }
                }

                @Override
                public void onComplete() {
                    logCallbacks.remove(serverId);
                    Log.info("Log callback removed for server %s", serverId);
                }
            };

            Const.executorService.execute(() -> {
                dockerClient.logContainerCmd(containerId)
                        .withStdOut(true)
                        .withStdErr(true)
                        .withFollowStream(true)
                        .exec(callback);
            });

            return callback;
        });
    }
}
