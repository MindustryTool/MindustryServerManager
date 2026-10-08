package server.service;

import java.io.Closeable;

import java.io.File;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import common.content.MapMetadata;
import common.content.Mod;
import common.player.PlayerInfo;
import common.player.PlayerRecordPage;
import common.player.RecentPlayer;
import common.server.ServerConfig;
import common.server.ServerSnapshot;
import common.server.ServerConfigMessage;
import common.server.ServerStatus;
import common.server.StartServer;
import common.event.ServerEvents.LogEvent;
import common.network.NodeRemoveReason;
import gateway.wire.StreamReply;
import server.types.data.NodeUsage;
import server.types.data.MisMatchType;
import server.types.data.ServerMisMatch;
import common.player.Login;
import common.content.ManagerMap;
import common.content.ManagerMod;
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
    private final ConcurrentHashMap<UUID, ReconcileStatus> reconcileStates = new ConcurrentHashMap<>();

    private final LoadingCache<String, ReentrantLock> locks = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(10))
            .build(key -> new ReentrantLock());

    static final Duration EMPTY_REMOVE_AFTER = Duration.ofMinutes(20);
    static final Duration UNREACHABLE_REMOVE_AFTER = Duration.ofMinutes(5);

    enum Phase {
        IDLE, PENDING, ACTING, REMOVING
    }

    static final class ReconcileStatus {
        Phase phase = Phase.IDLE;
        Instant since;
        Instant failingSince;
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
        scheduler.scheduleWithFixedDelay(this::reconcileTick, 1, 1, TimeUnit.MINUTES);
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
        lockedWith(request.getId(), () -> hostLocked(request));
    }

    void lockedRecreate(ServerConfig request) {
        lockedWith(request.getId(), () -> {
            remove(request.getId(), NodeRemoveReason.CONFIG_DRIFT);
            hostLocked(request);
        });
    }

    private void lockedWith(UUID serverId, Runnable action) {
        ReentrantLock lock = locks.get(serverId.toString());
        lock.lock();
        try {
            action.run();
        } finally {
            lock.unlock();
        }
    }

    void hostLocked(ServerConfig request) {
        {
            UUID serverId = request.getId();

            if (gatewayService.isHosting(serverId)) {
                storeDesiredConfig(serverId, request);
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

            nodeManager.writeFile(serverId, "server.json", toDesiredBytes(jwt, request));

            // Overwrite plugin jar with the bundled controller plugin
            nodeManager.writeFile(serverId, "mods/plugin.jar", pluginBundle.downloadPlugin());
            nodeManager.create(request);

            eventBus.emit(LogEvent.info(serverId, "Connecting to gateway"));
            GatewayClient gatewayClient = gatewayService.of(serverId);

            try {
                gatewayClient.awaitSession(Duration.ofSeconds(120)).get(120, TimeUnit.SECONDS);
                gatewayClient.server().isHosting().get(5, TimeUnit.SECONDS);
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
                        .host(new StartServer()
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
                } catch (Exception e) {
                    eventBus.emit(LogEvent.error(serverId, "Failed to host server"));
                    Log.err("Can not check server status for server " + serverId, e);
                }
            }

            Log.err("Server waiting for hosting status timeout, serverId " + serverId);

            throw new ApiError(503,
                    "Server waiting for hosting status timeout, make sure host command is valid, current host command: "
                            + request.getHostCommand());
        }
    }

    public List<ServerMisMatch> getMismatch(UUID serverId, ServerConfig config) {
        storeDesiredConfig(serverId, config);
        var state = state(serverId);
        var mods = getMods(serverId).stream().filter(mod -> !mod.getName().equals("PluginLoader")).toList();
        return nodeManager.getMismatch(serverId, config, state, mods);
    }

    public void updateConfig(UUID serverId, ServerConfig config) {
        storeDesiredConfig(serverId, config);
    }

    private void storeDesiredConfig(UUID serverId, ServerConfig config) {
        Objects.requireNonNull(serverId, "serverId");
        Objects.requireNonNull(config, "config");

        String jwt = null;
        try {
            Fi existing = nodeManager.getFile(serverId, "server.json");
            if (existing.exists()) {
                ServerConfigMessage current = Utils.objectMapper.readValue(
                        existing.readBytes(), ServerConfigMessage.class);
                jwt = current.getJwt();
            }
        } catch (Exception e) {
            Log.warn("Failed to read server.json for @, overwriting", serverId);
        }

        if (jwt == null || jwt.isBlank()) {
            jwt = wsHandler.generateServerJwt(serverId);
        }

        nodeManager.writeFile(serverId, "server.json", toDesiredBytes(jwt, config));
    }

    private static byte[] toDesiredBytes(String jwt, ServerConfig config) {
        ServerConfigMessage next = new ServerConfigMessage()
                .setJwt(jwt)
                .setConfig(config)
                .setStartServer(new StartServer()
                        .setHostCommand(config.getHostCommand())
                        .setMode(config.getMode()));
        try {
            return Utils.objectMapper
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(next);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize server config", e);
        }
    }

    ServerConfig loadDesiredConfig(UUID serverId) {
        Objects.requireNonNull(serverId, "serverId");
        try {
            Fi existing = nodeManager.getFile(serverId, "server.json");
            if (!existing.exists()) {
                return null;
            }
            ServerConfigMessage current = Utils.objectMapper.readValue(
                    existing.readBytes(), ServerConfigMessage.class);
                    
            return current.getConfig();
        } catch (Exception e) {
            Log.warn("Failed to read desired config for @", serverId);
            return null;
        }
    }

    public List<ManagerMap> getManagerMaps() {
        return nodeManager.getManagerMaps();
    }

    public List<ManagerMod> getManagerMods() {
        return nodeManager.getManagerMods();
    }

    public List<MapMetadata> getMaps(UUID serverId) {
        return nodeManager.getMaps(serverId);
    }

    public List<Mod> getMods(UUID serverId) {
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

    public ServerSnapshot state(UUID serverId) {
        try {
            return gatewayService.of(serverId)
                    .server()
                    .getState()
                    .get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            return new ServerSnapshot().setServerId(serverId).setStatus(ServerStatus.DISCONNECT);
        }
    }

    public StreamReply getImage(UUID serverId) {
        try {
            if (!nodeManager.isRunning(serverId)) {
                return new StreamReply(new byte[1]);
            }

            return new StreamReply(gatewayService.of(serverId)
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

    public List<RecentPlayer> getRecentPlayers(UUID serverId) {
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

    public PlayerRecordPage getPlayersInfo(UUID serverId, int page, int size, Boolean banned, String filter) {
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

    public List<PlayerInfo> getPlayers(UUID serverId) {
        return state(serverId).getPlayers();
    }

    public void updatePlayer(UUID serverId, String uuid, Login payload) {
        gatewayService.of(serverId)
                .server()
                .updatePlayer(uuid, payload)
                .join();
    }

    void reconcileTick() {
        var running = nodeManager.list().stream()
                .filter(s -> s.meta().isPresent() && s.running())
                .map(s -> s.meta().get().getConfig())
                .toList();

        var runningIds = running.stream().map(ServerConfig::getId).toList();
        reconcileStates.keySet().removeIf(id -> !runningIds.contains(id));

        for (ServerConfig live : running) {
            try {
                reconcileServer(live);
            } catch (Exception e) {
                Log.err("Fail to reconcile server " + live.getId(), e);
            }
        }
    }

    void reconcileServer(ServerConfig live) {
        UUID serverId = live.getId();
        ReconcileStatus status = reconcileStates.computeIfAbsent(serverId, _ignore -> new ReconcileStatus());

        ServerSnapshot snapshot = state(serverId);

        if (isUnreachable(snapshot)) {
            handleFailure(status, live, serverId);
            return;
        }

        status.failingSince = null;

        if (!snapshot.getPlayers().isEmpty()) {
            status.since = null;
            status.phase = Phase.IDLE;
            return;
        }

        if (status.since == null) {
            status.since = Instant.now();
        }

        ServerConfig wish = loadDesiredConfig(serverId);

        List<ServerMisMatch> mismatches;
        try {
            mismatches = computeMismatches(serverId, wish, snapshot);
        } catch (Exception e) {
            handleFailure(status, live, serverId);
            return;
        }

        if (wish != null && !mismatches.isEmpty()) {
            status.phase = Phase.ACTING;
            eventBus.emit(LogEvent.info(serverId, "Reconcile drift, recreating"));
            try {
                lockedRecreate(wish);
            } catch (Exception e) {
                handleFailure(status, live, serverId);
                return;
            }
            status.since = Instant.now();
            status.phase = Phase.IDLE;
            eventBus.emit(LogEvent.info(serverId, "Reconcile done"));
            return;
        }

        status.phase = Phase.IDLE;

        if (!resolveAutoTurnOff(live, wish)) {
            return;
        }

        if (Duration.between(status.since, Instant.now()).compareTo(EMPTY_REMOVE_AFTER) >= 0) {
            status.phase = Phase.REMOVING;
            eventBus.emit(LogEvent.info(serverId, "[red][Orchestrator] Auto shut down server"));
            remove(serverId, NodeRemoveReason.NO_PLAYER);
            reconcileStates.remove(serverId);
        }
    }

    private void handleFailure(ReconcileStatus status, ServerConfig live, UUID serverId) {
        if (status.failingSince == null) {
            status.failingSince = Instant.now();
            Log.warn("Reconcile unreachable for " + serverId + ", arming reclaim");
        }

        if (Duration.between(status.failingSince, Instant.now())
                .compareTo(UNREACHABLE_REMOVE_AFTER) < 0) {
            status.phase = Phase.PENDING;
            return;
        }

        ServerConfig wish = loadDesiredConfig(serverId);
        boolean revive = Boolean.FALSE.equals(live.getIsAutoTurnOff()) && wish != null;

        status.phase = Phase.REMOVING;
        try {
            if (revive) {
                eventBus.emit(LogEvent.info(serverId, "Reconcile reclaim, reviving"));
                lockedRecreate(wish);
            } else {
                eventBus.emit(LogEvent.info(serverId, "Reconcile reclaim, removing"));
                remove(serverId, NodeRemoveReason.NOT_RESPONSE);
            }
        } catch (Exception e) {
            Log.err("Reconcile reclaim failed for " + serverId, e);
            return;
        }
        reconcileStates.remove(serverId);
    }

    private List<ServerMisMatch> computeMismatches(UUID serverId, ServerConfig wish, ServerSnapshot snapshot) {
        if (wish == null) {
            return List.of();
        }

        var mods = getMods(serverId).stream()
                .filter(mod -> !mod.getName().equals("PluginLoader")).toList();
        List<ServerMisMatch> mismatches = new ArrayList<>(
                nodeManager.getMismatch(serverId, wish, snapshot, mods));

        String liveHash = snapshot.getPluginHash();
        String wantHash = pluginBundle.getPluginVersion();
        if (liveHash != null && wantHash != null && !Objects.equals(liveHash, wantHash)) {
            mismatches.add(new ServerMisMatch()
                    .setType(MisMatchType.PLUGIN_JAR)
                    .setField("Plugin jar mismatch")
                    .setCurrent(liveHash)
                    .setExpected(wantHash));
        }

        return mismatches;
    }

    private static boolean isUnreachable(ServerSnapshot snapshot) {
        ServerStatus status = snapshot.getStatus();
        return status == ServerStatus.DISCONNECT
                || status == ServerStatus.NOT_RESPONSE
                || status == ServerStatus.UNSET;
    }

    private static boolean resolveAutoTurnOff(ServerConfig live, ServerConfig wish) {
        if (wish != null && wish.getIsAutoTurnOff() != null) {
            return wish.getIsAutoTurnOff();
        }
        return live.getIsAutoTurnOff() != null && live.getIsAutoTurnOff();
    }
}
