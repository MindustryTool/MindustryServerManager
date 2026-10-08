package server.service;

import static org.junit.jupiter.api.Assertions.*;

import java.io.Closeable;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.benmanes.caffeine.cache.Caffeine;

import arc.files.Fi;
import common.content.ManagerMap;
import common.content.ManagerMod;
import common.content.MapMetadata;
import common.content.Mod;
import common.network.NodeRemoveReason;
import common.player.PlayerInfo;
import common.server.ServerConfig;
import common.server.ServerMetadata;
import common.server.ServerSnapshot;
import common.server.ServerStatus;
import server.EnvConfig;
import server.manager.NodeManager;
import server.service.translation.TranslationService;
import server.types.data.NodeUsage;
import server.types.data.ServerMisMatch;
import server.types.data.ServerState;

class ServerServiceReconcileTest {

    @TempDir
    Path tempDir;

    private Path base;
    private FakeNodes nodes;
    private RecordingServerService service;

    record RemovedRecord(UUID id, NodeRemoveReason reason) {
    }

    static class FakeNodes implements NodeManager {
        final Path base;
        final List<UUID> running = new CopyOnWriteArrayList<>();
        final List<RemovedRecord> removed = new CopyOnWriteArrayList<>();
        List<ServerMisMatch> mismatches = List.of();
        ServerConfig runningConfig;

        FakeNodes(Path base) {
            this.base = base;
        }

        @Override
        public List<ServerState> list() {
            List<ServerState> states = new ArrayList<>();
            for (UUID id : running) {
                ServerConfig cfg = runningConfig != null ? runningConfig : new ServerConfig().setId(id);
                states.add(new ServerState()
                        .running(true)
                        .meta(Optional.of(new ServerMetadata().setConfig(cfg))));
            }
            return states;
        }

        @Override
        public void create(ServerConfig config) {
        }

        @Override
        public boolean remove(UUID id, NodeRemoveReason reason) {
            removed.add(new RemovedRecord(id, reason));
            return true;
        }

        @Override
        public List<ServerMisMatch> getMismatch(UUID id, ServerConfig config, ServerSnapshot state, List<Mod> mods) {
            return mismatches;
        }

        @Override
        public Closeable getNodeUsage(UUID serverId, Consumer<NodeUsage> onUsage, Consumer<Throwable> onError) {
            return () -> {
            };
        }

        @Override
        public List<ManagerMap> getManagerMaps() {
            return Collections.emptyList();
        }

        @Override
        public List<ManagerMod> getManagerMods() {
            return Collections.emptyList();
        }

        @Override
        public List<MapMetadata> getMaps(UUID serverId) {
            return Collections.emptyList();
        }

        @Override
        public List<Mod> getMods(UUID serverId) {
            return Collections.emptyList();
        }

        @Override
        public Object getFiles(UUID serverId, String path) {
            return Collections.emptyList();
        }

        @Override
        public Fi getFile(UUID serverId, String path) {
            return new Fi(base.resolve(serverId.toString()).resolve(path).toFile());
        }

        @Override
        public Fi getServerFolder() {
            return new Fi(base.toFile());
        }

        @Override
        public void writeFile(UUID serverId, String path, byte[] data) {
            try {
                Path target = base.resolve(serverId.toString()).resolve(path);
                Files.createDirectories(target.getParent());
                Files.write(target, data);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public boolean createFolder(UUID serverId, String path) {
            return getFile(serverId, path).mkdirs();
        }

        @Override
        public boolean deleteFile(UUID serverId, String path) {
            Fi file = getFile(serverId, path);
            return file.exists() && file.delete();
        }

        @Override
        public boolean isRunning(UUID serverId) {
            return true;
        }

        @Override
        public void onKilled(Consumer<UUID> onKilled) {
        }
    }

    static class RecordingServerService extends ServerService {
        volatile ServerSnapshot nextState = new ServerSnapshot().setStatus(ServerStatus.ONLINE);
        final List<UUID> hostCalls = new CopyOnWriteArrayList<>();
        final List<RemovedRecord> removed = new CopyOnWriteArrayList<>();

        RecordingServerService(GatewayService gatewayService, NodeManager nodeManager, EventBus eventBus,
                ApiService apiService, WsHandler wsHandler, PluginBundleService pluginBundle) {
            super(gatewayService, nodeManager, eventBus, apiService, wsHandler, pluginBundle);
        }

        @Override
        public ServerSnapshot state(UUID serverId) {
            return nextState;
        }

        @Override
        public List<Mod> getMods(UUID serverId) {
            return Collections.emptyList();
        }

        @Override
        void hostLocked(ServerConfig request) {
            hostCalls.add(request.getId());
        }

        @Override
        public void remove(UUID serverId, NodeRemoveReason reason) {
            removed.add(new RemovedRecord(serverId, reason));
        }
    }

    @BeforeEach
    void setUp() {
        base = tempDir.resolve("servers");
        nodes = new FakeNodes(base);
        EnvConfig env = new EnvConfig(
                new EnvConfig.DockerEnv("image", "/data", null, null),
                new EnvConfig.ServerConfig(true, "token", "/data", "ws://localhost/gateway", "http://localhost/"),
                "test-signing-key");
        GatewayService gatewayService = new GatewayService(new EventBus(), env, nodes,
                new TranslationService(Caffeine.newBuilder().build()),
                new PluginBundleService(new byte[] { 1 }, "test"));
        WsHandler wsHandler = new WsHandler(gatewayService, nodes, "test-signing-key");
        service = new RecordingServerService(gatewayService, nodes, new EventBus(), new ApiService(), wsHandler,
                new PluginBundleService(new byte[] { 1 }, "test"));
    }

    private ServerConfig config(UUID serverId, int port, boolean autoTurnOff) {
        return new ServerConfig()
                .setId(serverId)
                .setName("test")
                .setDescription("")
                .setMode("survival")
                .setGamemode("survival")
                .setPort(port)
                .setEnv(Map.of())
                .setImage("image")
                .setHostCommand("host")
                .setIsHub(false)
                .setIsAutoTurnOff(autoTurnOff)
                .setIsDefault(false)
                .setIsOfficial(false)
                .setCpu(1f)
                .setMemory(512);
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<UUID, Object> statesMap() throws Exception {
        Field f = ServerService.class.getDeclaredField("reconcileStates");
        f.setAccessible(true);
        return (ConcurrentHashMap<UUID, Object>) f.get(service);
    }

    private void backdate(UUID serverId, String fieldName, Duration ago) throws Exception {
        Object entry = statesMap().get(serverId);
        assertNotNull(entry, "reconcile status should exist after a tick");
        Field f = entry.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(entry, Instant.now().minus(ago));
    }

    private static PlayerInfo player() {
        try {
            var ctor = PlayerInfo.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static ServerMisMatch drift() {
        return new ServerMisMatch()
                .setField("Port mismatch")
                .setCurrent("6567")
                .setExpected("7000");
    }

    @Test
    void configDriftRecreatesNotRemoves() {
        UUID serverId = UUID.randomUUID();
        nodes.running.add(serverId);
        nodes.runningConfig = config(serverId, 6567, true);
        service.updateConfig(serverId, config(serverId, 7000, true));
        nodes.mismatches = List.of(drift());

        service.reconcileTick();

        assertTrue(service.hostCalls.contains(serverId), "drift must recreate");
        assertTrue(service.removed.stream()
                .noneMatch(r -> r.reason() == NodeRemoveReason.NO_PLAYER),
                "drift must never auto-remove for idle");
        assertEquals(1, service.removed.stream()
                .filter(r -> r.reason() == NodeRemoveReason.CONFIG_DRIFT).count(),
                "recreate must use the config-drift reason");
    }

    @Test
    void cleanEmptyServerRemovedAfter20m() throws Exception {
        UUID serverId = UUID.randomUUID();
        nodes.running.add(serverId);
        nodes.runningConfig = config(serverId, 6567, true);
        nodes.mismatches = List.of();

        service.reconcileTick();
        backdate(serverId, "since", Duration.ofMinutes(21));
        service.reconcileTick();

        assertTrue(service.removed.stream()
                .anyMatch(r -> r.id().equals(serverId) && r.reason() == NodeRemoveReason.NO_PLAYER),
                "clean empty server must be removed after 20m");
    }

    @Test
    void cleanEmptyServerNotRemovedBefore20m() {
        UUID serverId = UUID.randomUUID();
        nodes.running.add(serverId);
        nodes.runningConfig = config(serverId, 6567, true);
        nodes.mismatches = List.of();

        service.reconcileTick();

        assertTrue(service.removed.isEmpty(), "server under 20m empty must not be removed");
    }

    @Test
    void playersBlockActionAndRemove() {
        UUID serverId = UUID.randomUUID();
        nodes.running.add(serverId);
        nodes.runningConfig = config(serverId, 6567, true);
        service.updateConfig(serverId, config(serverId, 7000, true));
        nodes.mismatches = List.of(drift());

        service.nextState = new ServerSnapshot().setStatus(ServerStatus.ONLINE)
                .setPlayers(List.of(player()));
        service.reconcileTick();

        assertTrue(service.hostCalls.isEmpty(), "players online must block recreate");
        assertTrue(service.removed.isEmpty(), "players online must block remove");
    }

    @Test
    void unreachableStaysQuietBefore5m() {
        UUID serverId = UUID.randomUUID();
        nodes.running.add(serverId);
        nodes.runningConfig = config(serverId, 6567, true);
        service.nextState = new ServerSnapshot().setStatus(ServerStatus.DISCONNECT);

        service.reconcileTick();

        assertTrue(service.hostCalls.isEmpty(), "unreachable under 5m must not be acted on");
        assertTrue(service.removed.isEmpty(), "unreachable under 5m must not be removed");
    }

    @Test
    void unreachableOptOutRevivedAfter5m() throws Exception {
        UUID serverId = UUID.randomUUID();
        nodes.running.add(serverId);
        nodes.runningConfig = config(serverId, 6567, false);
        service.updateConfig(serverId, config(serverId, 6567, false));
        service.nextState = new ServerSnapshot().setStatus(ServerStatus.DISCONNECT);

        service.reconcileTick();
        backdate(serverId, "failingSince", Duration.ofMinutes(6));
        service.reconcileTick();

        assertTrue(service.hostCalls.contains(serverId), "opt-out unreachable must be revived");
    }

    @Test
    void unreachableAutoOffRemovedAfter5m() throws Exception {
        UUID serverId = UUID.randomUUID();
        nodes.running.add(serverId);
        nodes.runningConfig = config(serverId, 6567, true);
        service.nextState = new ServerSnapshot().setStatus(ServerStatus.DISCONNECT);

        service.reconcileTick();
        backdate(serverId, "failingSince", Duration.ofMinutes(6));
        service.reconcileTick();

        assertTrue(service.removed.stream()
                .anyMatch(r -> r.id().equals(serverId) && r.reason() == NodeRemoveReason.NOT_RESPONSE),
                "auto-off unreachable must be removed with NOT_RESPONSE");
    }

    @Test
    void recoveryClearsFailTimer() throws Exception {
        UUID serverId = UUID.randomUUID();
        nodes.running.add(serverId);
        nodes.runningConfig = config(serverId, 6567, true);

        service.nextState = new ServerSnapshot().setStatus(ServerStatus.DISCONNECT);
        service.reconcileTick();

        service.nextState = new ServerSnapshot().setStatus(ServerStatus.ONLINE).setPlayers(List.of(player()));
        service.reconcileTick();

        Object entry = statesMap().get(serverId);
        assertNotNull(entry);
        Field f = entry.getClass().getDeclaredField("failingSince");
        f.setAccessible(true);
        assertNull(f.get(entry), "recovery must clear the fail timer");
        assertTrue(service.removed.isEmpty());
    }
}
