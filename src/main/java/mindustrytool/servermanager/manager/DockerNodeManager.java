package mindustrytool.servermanager.manager;

import java.io.File;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.channels.AsynchronousCloseException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;

import arc.util.Log;
import jakarta.annotation.PostConstruct;
import mindustrytool.servermanager.EnvConfig;
import mindustrytool.servermanager.config.Const;
import mindustrytool.servermanager.types.data.NodeUsage;
import mindustrytool.servermanager.types.data.ServerConfig;
import mindustrytool.servermanager.types.data.ServerMetadata;
import mindustrytool.servermanager.types.data.ServerState;
import mindustrytool.servermanager.types.data.WebhookMessage;
import mindustrytool.servermanager.types.data.ServerMisMatch;
import mindustrytool.servermanager.types.event.LogEvent;
import mindustrytool.servermanager.types.response.ModDto;
import mindustrytool.servermanager.types.response.StatsDto;
import mindustrytool.servermanager.utils.Utils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.FluxSink.OverflowStrategy;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
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
public class DockerNodeManager extends NodeManager {
    private final DockerClient dockerClient;
    private final EnvConfig envConfig;
    private final WebClient webClient;

    private final Map<UUID, ResultCallback.Adapter<Frame>> logCallbacks = new ConcurrentHashMap<>();

    public DockerNodeManager(EnvConfig envConfig, WebClient webClient) {
        this.envConfig = envConfig;
        this.webClient = webClient;

        var dockerConfig = DefaultDockerClientConfig.createDefaultConfigBuilder()//
                .withDockerHost("unix:///var/run/docker.sock")
                .build();

        var dockerHttpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(dockerConfig.getDockerHost())//
                .sslConfig(dockerConfig.getSSLConfig())//
                .connectionTimeout(Duration.ofSeconds(2))//
                .responseTimeout(Duration.ofSeconds(5))//
                .build();

        this.dockerClient = DockerClientImpl.getInstance(dockerConfig, dockerHttpClient);
    }

    @Override
    public Flux<LogEvent> create(ServerConfig request) {
        return Flux.create(emitter -> {
            try {
                var serverId = request.getId();
                try {
                    emitter.next(LogEvent.info(serverId, "Pulling image: " + request.getImage()));

                    dockerClient.pullImageCmd(request.getImage())
                            .exec(new ResultCallback.Adapter<PullResponseItem>())
                            .awaitCompletion();

                    emitter.next(LogEvent.info(serverId, "Image pulled"));
                } catch (InterruptedException e) {
                    emitter.next(LogEvent.error(serverId, e.getMessage()));
                }

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

                        emitter.complete();
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

                var currentMetadata = ServerMetadata.from(request)
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
                        // .withHealthcheck(
                        // new HealthCheck()//
                        // .withInterval(100000000000L)//
                        // .withRetries(5)
                        // .withTimeout(100000000000L) // 10 seconds
                        // .withTest(List.of(
                        // "CMD",
                        // "sh",
                        // "-c",
                        // "wget --spider -q http://" + serverId.toString()
                        // + ":9999/ok || exit 1")))
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

                emitter.next(LogEvent.info(serverId, "Container " + containerId + " started"));
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
            return Mono.error(new IllegalArgumentException("Server not found"));
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

            if (metadata == null) {
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
    public Flux<ServerMisMatch> getMismatch(UUID id, ServerConfig config, StatsDto stats, List<ModDto> mods) {
        var optional = findContainerByServerId(id);

        if (optional.isEmpty()) {
            return Flux.error(new IllegalArgumentException("Server not found"));
        }

        var container = optional.get();

        var meta = readMetadataFromContainer(container).orElseThrow();

        List<ServerMisMatch> result = ServerMisMatch.from(meta, config, stats, mods);

        var serverImage = dockerClient.inspectImageCmd(meta.getConfig().getImage()).exec();

        if (!meta.getServerImageHash().equals(serverImage.getId())) {
            result.add(new ServerMisMatch("serverImage", serverImage.getId(), meta.getServerImageHash()));
        }

        return Flux.fromIterable(result);
    }

    @Override
    public File getFile(UUID serverId, String path) {
        if (path.contains("..")) {
            throw new IllegalArgumentException("Invalid path, contains '..'");
        }

        var decodedPath = URLDecoder.decode(path, StandardCharsets.UTF_8);

        return Paths
                .get(Const.volumeFolderPath,
                        "servers",
                        serverId.toString(),
                        "config",
                        decodedPath)
                .toFile();

    }

    @Override
    public boolean deleteFile(UUID serverId, String path) {
        var file = getFile(serverId, path);

        return deleteFile(file);
    }

    private boolean deleteFile(File file) {
        if (!file.exists()) {
            return false;
        }

        if (file.isDirectory()) {
            for (File f : file.listFiles()) {
                deleteFile(f);
            }
        }

        file.delete();
        return true;
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
                Log.err(e);
            }
        }
    }

    @PostConstruct
    public void init() {
        final Set<String> ignoredEvents = Set.of("exec_create", "exec_start", "exec_die", "exec_detach");

        dockerClient.eventsCmd()
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

                        if (status.equalsIgnoreCase("start")) {

                            var containers = dockerClient.listContainersCmd()
                                    .withIdFilter(List.of(containerId))
                                    .exec();

                            if (containers.size() != 1) {
                                return;
                            }

                            var container = containers.get(0);

                            readMetadataFromContainer(container)
                                    .ifPresent(metadata -> logCallbacks.computeIfAbsent(
                                            metadata.getConfig().getId(),
                                            id -> createLogCallack(containerId, id)));
                        }

                        String name = Optional.ofNullable(event.getActor().getAttributes().get(Const.serverLabelName))
                                .map(attr -> Utils.readJsonAsClass(attr, ServerMetadata.class))
                                .map(meta -> meta.getConfig().getName())
                                .orElse(event.getFrom());

                        String message = "Server %s %s %s".formatted(name, event.getStatus(), action);

                        Log.info(message);

                        webClient.post()
                                .uri(Const.discordWebhook)
                                .bodyValue(new WebhookMessage(message))
                                .retrieve()
                                .bodyToMono(Void.class)
                                .onErrorResume(Exception.class, ex -> {
                                    ex.printStackTrace();
                                    return Mono.empty();
                                }).subscribe();
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

                fire(LogEvent.info(serverId, message));
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

        logCallbacks.put(serverId, callback);

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
                    emitter.complete();
                }

                @Override
                public void onError(Throwable throwable) {
                    Log.info("[" + serverId + "] Stats stream error: " + throwable.getMessage());

                    if (!emitter.isCancelled()) {
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
}
