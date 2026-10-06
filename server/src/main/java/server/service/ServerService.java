package server.service;

import java.io.Closeable;

import java.io.File;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;

import arc.files.Fi;
import arc.util.Log;
import com.fasterxml.jackson.core.JsonProcessingException;
import dto.MapDto;
import dto.ModDto;
import dto.PlayerDto;
import dto.PlayerInfoPageDto;
import dto.RecentPlayerDto;
import dto.ServerConfig;
import dto.ServerStateDto;
import dto.ServerConfigDto;
import dto.ServerStatus;
import dto.StartServerDto;
import events.ServerEvents.LogEvent;
import gateway.rpc.WsRpcChannel;
import enums.NodeRemoveReason;
import server.types.data.NodeUsage;
import server.types.data.ServerMisMatch;
import dto.LoginDto;
import dto.ManagerMapDto;
import dto.ManagerModDto;
import server.config.Const;
import server.manager.NodeManager;
import server.service.GatewayService.GatewayClient;
import server.utils.ApiError;
import server.utils.Utils;

public class ServerService {
    private static final long RPC_TIMEOUT_SECONDS = 30L;

    private final GatewayService gatewayService;
    private final NodeManager nodeManager;
    private final EventBus eventBus;
    private final ApiService apiService;
    private final WsHandler wsHandler;
    private final PluginBundleService pluginBundle;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final ConcurrentHashMap<UUID, EnumSet<ServerFlag>> serverFlags = new ConcurrentHashMap<>();

    private final LoadingCache<String, ReentrantLock> locks = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(10))
            .build(key -> new ReentrantLock());

    private enum ServerFlag {
        KILL, NOT_RESPONSE, RESTART
    }

    public ServerService(GatewayService gatewayService, NodeManager nodeManager, EventBus eventBus,
            ApiService apiService, WsHandler wsHandler, PluginBundleService pluginBundle) {
        this.gatewayService = gatewayService;
        this.nodeManager = nodeManager;
        this.eventBus = eventBus;
        this.apiService = apiService;
        this.wsHandler = wsHandler;
        this.pluginBundle = pluginBundle;

        init();
    }

    private void init() {
        scheduler.scheduleWithFixedDelay(this::autoTurnOffCron, 5, 10, TimeUnit.MINUTES);
        scheduler.scheduleWithFixedDelay(this::removeOldServer, 0, 24, TimeUnit.HOURS);
    }

    private void removeOldServer() {
        int removeAfterDays = 120;

        for (Fi file : Const.serverFolder.list()) {
            if (file.isDirectory()) {
                String serverId = file.name();
                try {
                    File previewFile = nodeManager.getFile(UUID.fromString(serverId), "server.json").file();

                    Instant lastModifiedTime = previewFile.exists()
                            ? Files.getLastModifiedTime(previewFile.toPath()).toInstant()
                            : LocalDateTime.of(2026, 1, 1, 0, 0).toInstant(ZoneOffset.UTC);

                    Instant canBeDeletedAt = lastModifiedTime.plus(Duration.ofDays(removeAfterDays));

                    if (canBeDeletedAt.isBefore(Instant.now())) {
                        remove(UUID.fromString(serverId), NodeRemoveReason.OLD);
                        file.deleteDirectory();
                        Log.info("Remove old server " + serverId);
                    }
                } catch (Exception e) {
                    Log.err("Can not remove server " + serverId, e);
                }
            }
        }
    }

    public void remove(UUID serverId, NodeRemoveReason reason) {
        gatewayService.terminate(serverId, reason);
    }

    public boolean pause(UUID serverId) {
        try {
            return gatewayService.of(serverId).server().pause().get(10, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new RuntimeException("Can not pause server", e);
        }
    }

    public void host(ServerConfig request) {
        ReentrantLock lock = locks.get(request.getId().toString());

        lock.lock();

        try {
            UUID serverId = request.getId();

            if (gatewayService.isHosting(serverId)) {
                return;
            }

            var unusedFiles = List.of("mindustry-tool-plugins", "mods/loader.jar", "WEBSOCKET.txt");

            for (String file : unusedFiles) {
                if (nodeManager.deleteFile(serverId, file)) {
                    Log.info("Delete old file: " + file);
                }
            }

            eventBus.emit(LogEvent.info(serverId, "Generate server config file"));
            String jwt = wsHandler.generateServerJwt(serverId);
            ServerConfigDto serverConfig = new ServerConfigDto()
                    .setJwt(jwt)
                    .setStartServer(new StartServerDto()
                            .setHostCommand(request.getHostCommand())
                            .setMode(request.getMode()));

            try {
                nodeManager.writeFile(serverId, "server.json",
                        Utils.objectMapper
                                .writerWithDefaultPrettyPrinter()
                                .writeValueAsBytes(serverConfig));
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to serialize server config", e);
            }

            nodeManager.create(request);

            // Overwrite plugin jar with the bundled controller plugin
            nodeManager.writeFile(serverId, "mods/plugin.jar", pluginBundle.downloadPlugin());
            Log.info("Write mods/plugin.jar");

            eventBus.emit(LogEvent.info(serverId, "Connecting to gateway"));
            GatewayClient gatewayClient = gatewayService.of(serverId);

            try {
                gatewayClient.server().isHosting().get(120, TimeUnit.SECONDS);
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                throw new ApiError(502, "Can not connect to gateway", e);
            }

            eventBus.emit(LogEvent.info(serverId, "Waiting for server to start"));

            String gamemode = request.getGamemode();

            if (gamemode == null || gamemode.isEmpty()) {
                gamemode = request.getMode();
            }

            String[] preHostCommand = {
                    "config name %s".formatted(request.getName()),
                    request.getDescription().isEmpty() ? "" : "config desc %s".formatted(request.getDescription()),
                    "config port 6567",
                    "gamemode " + gamemode,
                    "version"
            };

            try {
                gatewayClient.server().sendCommand(preHostCommand).get(5, TimeUnit.SECONDS);

                eventBus.emit(LogEvent.info(serverId, "Host server"));

                gatewayClient.server()
                        .host(new StartServerDto()
                                .setHostCommand(request.getHostCommand())
                                .setMode(request.getMode()))
                        .get(15, TimeUnit.SECONDS);

                eventBus.emit(LogEvent.info(serverId, "Wait for server status"));
            } catch (Exception e) {
                throw new ApiError(500, "Fail to send host command", e);
            }

            for (int i = 0; i < 120; i++) {
                try {
                    if (gatewayClient.server().isHosting().get(1000, TimeUnit.MILLISECONDS)) {
                        eventBus.emit(LogEvent.info(serverId, "Server hosting"));
                        return;
                    }
                } catch (InterruptedException | ExecutionException | TimeoutException e) {
                    Log.err("Can not check server status", e);
                }
            }

            Log.err("Server waiting for hosting status timeout, serverId " + serverId);

            throw new ApiError(503,
                    "Server waiting for hosting status timeout, make sure host command is valid, current host command: "
                            + request.getHostCommand());
        } finally {
            lock.unlock();
        }
    }

    public List<ServerMisMatch> getMismatch(UUID serverId, ServerConfig config) {
        var state = state(serverId);
        var mods = getMods(serverId).stream().filter(mod -> !mod.getName().equals("PluginLoader")).toList();
        return nodeManager.getMismatch(serverId, config, state, mods);
    }

    public List<ManagerMapDto> getManagerMaps() {
        return nodeManager.getManagerMaps();
    }

    public List<ManagerModDto> getManagerMods() {
        return nodeManager.getManagerMods();
    }

    public List<MapDto> getMaps(UUID serverId) {
        return nodeManager.getMaps(serverId);
    }

    public List<ModDto> getMods(UUID serverId) {
        return nodeManager.getMods(serverId);
    }

    public Object getFiles(UUID serverId, String path) {
        Fi file = nodeManager.getFile(serverId, path);
        boolean exists = file.exists();
        boolean isMapImage = path != null && path.endsWith(".msav.png");
        String failedPath = path != null ? path.replace(".msav.png", ".msav.failed.png") : null;
        String mapFilePath = path != null ? path.replace(".msav.png", ".msav") : null;

        Fi failedFile = nodeManager.getFile(serverId, failedPath);
        boolean failedExists = failedFile.exists();

        if (isMapImage && !exists && !failedExists) {
            Fi mapFile = nodeManager.getFile(serverId, mapFilePath);
            byte[] mapBytes = mapFile.readBytes();

            if (mapBytes.length > 0) {
                Log.info("Generate map preview for file " + mapFilePath + " on server " + serverId);

                apiService.getMapPreview(mapBytes).whenComplete((res, err) -> {
                    if (res != null) {
                        try {
                            nodeManager.writeFile(serverId, path, res.readAllBytes());
                        } catch (Exception e) {
                            Log.err("Fail to write map preview for file " + path + " on server " + serverId, e);
                        }
                    }

                    if (err != null) {
                        Throwable cause = err;

                        while ((cause instanceof ExecutionException || cause instanceof CompletionException)
                                && cause.getCause() != null) {
                            cause = cause.getCause();
                        }

                        if (cause instanceof ApiError apiError && apiError.status < 500) {
                            try {
                                nodeManager.writeFile(serverId, failedPath, new byte[0]);
                            } catch (Exception e) {
                                Log.err("Fail to write failed map preview for file " + path + " on server " + serverId,
                                        e);
                            }
                        } else {
                            Log.err("Fail to generate map preview for file " + path + " on server " + serverId, cause);
                        }
                    }
                });
            } else {
                Log.err("Invalid map file: [" + mapFilePath + "] on server " + serverId);
            }
        }

        return nodeManager.getFiles(serverId, path);
    }

    public void writeFile(UUID serverId, String path, byte[] bytes, String filename) {
        nodeManager.writeFile(serverId, path, bytes);
        if (filename != null && filename.endsWith("msav")) {
            CompletableFuture.runAsync(() -> {
                try {
                    byte[] image = apiService.getMapPreview(bytes).get(5, TimeUnit.MINUTES).readAllBytes();
                    byte[] preview = Utils.toByteArray(Utils.toPreviewImage(Utils.fromBytes(image)));
                    nodeManager.writeFile(serverId, path + ".png", preview);
                } catch (Exception e) {
                    Log.err("Fail to write map preview for file " + path + " on server " + serverId, e);
                }
            });
        }
    }

    public boolean createFolder(UUID serverId, String path) {
        return nodeManager.createFolder(serverId, path);
    }

    public boolean deleteFile(UUID serverId, String path) {
        return nodeManager.deleteFile(serverId, path);
    }

    public Closeable getUsage(UUID serverId, Consumer<NodeUsage> onUsage, Consumer<Throwable> onError) {
        return nodeManager.getNodeUsage(serverId, onUsage, onError);
    }

    public ServerStateDto state(UUID serverId) {
        try {
            return gatewayService.of(serverId)
                    .server()
                    .getState()
                    .get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            return new ServerStateDto().setServerId(serverId).setStatus(ServerStatus.DISCONNECT);
        }
    }

    public WsRpcChannel.StreamReply getImage(UUID serverId) {
        try {
            if (!nodeManager.isRunning(serverId)) {
                return new WsRpcChannel.StreamReply(new byte[1]);
            }

            return new WsRpcChannel.StreamReply(gatewayService.of(serverId)
                    .server()
                    .getImage()
                    .get(60, TimeUnit.SECONDS));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public Map<String, Long> getKickedIps(UUID serverId) {
        try {
            if (!nodeManager.isRunning(serverId)) {
                throw new RuntimeException("Server is not running");
            }

            return gatewayService.of(serverId)
                    .server()
                    .getKickedIps()
                    .get(RPC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public List<RecentPlayerDto> getRecentPlayers(UUID serverId) {
        try {
            if (!nodeManager.isRunning(serverId)) {
                throw new RuntimeException("Server is not running");
            }

            return gatewayService.of(serverId)
                    .server()
                    .getRecentPlayers()
                    .get(RPC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public boolean deleteKickedIp(UUID serverId, String ip) {
        try {
            if (!nodeManager.isRunning(serverId)) {
                throw new RuntimeException("Server is not running");
            }

            return gatewayService.of(serverId)
                    .server()
                    .deleteKickedIp(ip)
                    .get(RPC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public PlayerInfoPageDto getPlayersInfo(UUID serverId, int page, int size, Boolean banned, String filter) {
        try {
            if (!nodeManager.isRunning(serverId)) {
                throw new RuntimeException("Server is not running");
            }

            return gatewayService.of(serverId)
                    .server()
                    .getPlayersInfo(page, size, banned, filter)
                    .get(RPC_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public List<PlayerDto> getPlayers(UUID serverId) {
        return state(serverId).getPlayers();
    }

    public void updatePlayer(UUID serverId, String uuid, LoginDto payload) {
        gatewayService.of(serverId)
                .server()
                .updatePlayer(uuid, payload)
                .join();
    }

    private void autoTurnOffCron() {
        List<ServerConfig> servers = nodeManager.list().stream()
                .filter(s -> s.meta().isPresent() && s.running())
                .map(s -> s.meta().get().getConfig())
                .toList();

        var serversId = servers.stream().map(ServerConfig::getId).toList();
        serverFlags.entrySet().removeIf(entry -> entry.getValue().isEmpty() || !serversId.contains(entry.getKey()));

        servers.forEach(config -> {
            try {
                checkRunningServer(config, true);
            } catch (Exception e) {
                Log.err("Fail to check running server " + config.getId(), e);
            }
        });
    }

    private void checkRunningServer(ServerConfig config, boolean shouldAutoTurnOff) {
        var serverId = config.getId();
        var flag = serverFlags.computeIfAbsent(serverId, (_ignore) -> EnumSet.noneOf(ServerFlag.class));

        if (!config.getIsAutoTurnOff()) {
            return;
        }

        ServerStateDto state = state(serverId);

        boolean shouldKill = state.getPlayers().isEmpty();

        if (shouldKill && shouldAutoTurnOff) {
            if (flag.contains(ServerFlag.KILL)) {
                flag.remove(ServerFlag.KILL);
                eventBus.emit(LogEvent.info(serverId, "[red][Orchestrator] Auto shut down server"));
                remove(serverId, NodeRemoveReason.NO_PLAYER);
            } else {
                flag.add(ServerFlag.KILL);
                eventBus.emit(LogEvent.info(serverId, "[red][Orchestrator] No players, flag to kill"));
            }
        } else {
            flag.remove(ServerFlag.KILL);
        }
    }
}
