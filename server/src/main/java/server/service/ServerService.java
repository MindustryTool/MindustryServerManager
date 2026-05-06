package server.service;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

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
import lombok.extern.slf4j.Slf4j;
import server.types.data.NodeUsage;
import server.types.data.ServerMisMatch;
import dto.LoginDto;
import dto.ManagerMapDto;
import dto.ManagerModDto;
import server.EnvConfig;
import server.manager.NodeManager;
import server.service.GatewayService.GatewayClient;
import server.utils.ApiError;
import server.utils.Utils;

@Slf4j
public class ServerService {
    private final GatewayService gatewayService;
    private final NodeManager nodeManager;
    private final EventBus eventBus;
    private final ApiService apiService;
    private final WsHandler wsHandler;
    private final EnvConfig envConfig;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final ConcurrentHashMap<UUID, EnumSet<ServerFlag>> serverFlags = new ConcurrentHashMap<>();
    private final List<Consumer<BaseEvent>> eventListeners = new LinkedList<>();
    private final ConcurrentHashMap<UUID, Consumer<LogEvent>> hostListeners = new ConcurrentHashMap<>();
    private final ArrayList<BaseEvent> buffer = new ArrayList<>();

    private enum ServerFlag {
        KILL, NOT_RESPONSE, RESTART
    }

    public ServerService(GatewayService gatewayService, NodeManager nodeManager, EventBus eventBus,
            ApiService apiService, WsHandler wsHandler, EnvConfig envConfig) {
        this.gatewayService = gatewayService;
        this.nodeManager = nodeManager;
        this.eventBus = eventBus;
        this.apiService = apiService;
        this.wsHandler = wsHandler;
        this.envConfig = envConfig;

        init();
    }

    private void init() {
        eventBus.on(event -> {
            synchronized (buffer) {
                if (eventListeners.isEmpty()) {
                    buffer.add(event);
                    if (buffer.size() > 1000)
                        buffer.remove(0);
                } else {
                    eventListeners.forEach(listener -> listener.accept(event));
                }
            }

            if (event instanceof LogEvent logEvent) {
                var listener = hostListeners.get(event.getServerId());
                if (listener != null)
                    listener.accept(logEvent);
            }
        });

        scheduler.scheduleAtFixedRate(this::autoConnectAndHostCron, 30, 30, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(this::autoTurnOffCron, 5, 5, TimeUnit.MINUTES);
    }

    public void addEventListener(Consumer<BaseEvent> listener) {
        synchronized (buffer) {
            ArrayList<BaseEvent> copy = new ArrayList<>(buffer);
            buffer.clear();
            copy.forEach(listener::accept);
            eventListeners.add(listener);
        }
    }

    public void removeEventListener(Consumer<BaseEvent> listener) {
        synchronized (buffer) {
            eventListeners.remove(listener);
        }
    }

    public CompletableFuture<Void> remove(UUID serverId, NodeRemoveReason reason) {
        eventBus.emit(new ServerEvents.StopEvent(serverId, reason));
        return nodeManager.remove(serverId, reason);
    }

    public CompletableFuture<Boolean> pause(UUID serverId) {
        return gatewayService.of(serverId).server().pause();
    }

    public void host(ServerConfig request) {
        UUID serverId = request.getId();

        try {
            Log.info("Delete old loader.jar");
            nodeManager.deleteFile(serverId, "mindustry-tool-plugins");
            nodeManager.deleteFile(serverId, "mods/loader.jar");

            Fi websocketFile = nodeManager.getFile(serverId, "WEBSOCKET.txt");

            if (!websocketFile.exists()) {
                Log.info("Generate websocket file");
                String jwt = wsHandler.generateServerJwt(serverId, envConfig.serverConfig().securityKey());
                nodeManager.writeFile(serverId, "WEBSOCKET.txt", jwt.getBytes());
            }

            Log.info("Server not hosting, create server");
            nodeManager.create(request);

            Log.info("Connecting to gateway...");
            GatewayClient gatewayClient = gatewayService.of(serverId);
            var serverGateway = gatewayClient.server();

            Log.info("Waiting for server to start");

            boolean isHosting = false;
            for (int i = 0; i < 1200; i++) {
                try {
                    isHosting = serverGateway.isHosting().get(100, TimeUnit.MILLISECONDS);
                    if (isHosting) {
                        break;
                    }
                } catch (Exception e) {
                    Log.err("Server not hosting, try again", e);
                }
            }

            if (isHosting) {
                Log.info("Server is hosting, do nothing");
                return;
            }

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
                serverGateway.sendCommand(preHostCommand).get(5, TimeUnit.SECONDS);
                Log.info("Config done");

                Log.info("Host server");
                serverGateway.host(request).get(15, TimeUnit.SECONDS);

                Log.info("Wait for server status");
                for (int i = 0; i < 600; i++) {
                    if (serverGateway.isHosting().get(100, TimeUnit.MILLISECONDS)) {
                        Log.info("Server hosting");
                        return;
                    }
                }
                throw new ApiError(502, "Can not host server");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        } finally {
            hostListeners.remove(serverId);
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
        return nodeManager.getFiles(serverId, path);
    }

    public boolean isFileExists(UUID serverId, String path) {
        return nodeManager.getFile(serverId, path).exists();
    }

    public void writeFile(UUID serverId, String path, byte[] bytes, String filename) {
        nodeManager.writeFile(serverId, path, bytes);
        if (filename != null && filename.endsWith("msav")) {
            CompletableFuture.runAsync(() -> {
                try {
                    byte[] image = apiService.getMapPreview(bytes).join();
                    byte[] preview = Utils.toByteArray(Utils.toPreviewImage(Utils.fromBytes(image)));
                    nodeManager.writeFile(serverId, path + ".png", preview);
                } catch (Exception e) {
                    Log.err(e);
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

    public void getUsage(UUID serverId, Consumer<NodeUsage> onUsage, Consumer<Throwable> onError) {
        nodeManager.getNodeUsage(serverId, onUsage, onError);
    }

    public ServerStateDto state(UUID serverId) {
        try {
            return gatewayService.of(serverId)
                    .server()
                    .getState()
                    .get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            Log.err(e.getMessage());
            return new ServerStateDto().setServerId(serverId).setStatus(ServerStatus.PAUSED);
        }
    }

    public byte[] getImage(UUID serverId) {
        try {
            return gatewayService.of(serverId)
                    .server()
                    .getImage()
                    .get(5, TimeUnit.SECONDS);
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

    private void autoConnectAndHostCron() {
        nodeManager.list().forEach(state -> {
            if (state.meta().isEmpty())
                return;
            var config = state.meta().get().getConfig();
            if (state.running()) {
                gatewayService.of(config.getId());
            } else if (!config.getIsAutoTurnOff()) {
                host(config);
            }
        });
    }

    private void autoTurnOffCron() {
        List<ServerConfig> servers = nodeManager.list().stream()
                .filter(s -> s.meta().isPresent() && s.running())
                .map(s -> s.meta().get().getConfig())
                .toList();

        var serversId = servers.stream().map(ServerConfig::getId).toList();
        serverFlags.entrySet().removeIf(entry -> entry.getValue().isEmpty() || !serversId.contains(entry.getKey()));

        servers.forEach(config -> checkRunningServer(config, true));
    }

    private void checkRunningServer(ServerConfig config, boolean shouldAutoTurnOff) {
        var serverId = config.getId();
        var flag = serverFlags.computeIfAbsent(serverId, (_ignore) -> EnumSet.noneOf(ServerFlag.class));

        ServerStateDto state = state(serverId);
        if (state.getStatus().equals(ServerStatus.NOT_RESPONSE)) {
            Log.err("Server [@] not response", serverId);
            if (flag.contains(ServerFlag.NOT_RESPONSE)) {
                eventBus.emit(LogEvent.info(serverId, "[red][Orchestrator] Kill server for not response"));
                remove(serverId, NodeRemoveReason.FETCH_EVENT_TIMEOUT);
            } else {
                eventBus.emit(LogEvent.info(serverId, "[red][Orchestrator] Server not response, flag to kill"));
                flag.add(ServerFlag.NOT_RESPONSE);
            }
            return;
        }

        flag.remove(ServerFlag.NOT_RESPONSE);
        if (!config.getIsAutoTurnOff())
            return;

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
