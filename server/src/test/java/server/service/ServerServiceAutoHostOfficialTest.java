package server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Closeable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
import common.server.ServerConfig;
import common.server.ServerConfigMessage;
import common.server.ServerMetadata;
import common.server.ServerSnapshot;
import common.server.StartServer;
import server.EnvConfig;
import server.manager.NodeManager;
import server.service.translation.TranslationService;
import server.types.data.NodeUsage;
import server.types.data.ServerMisMatch;
import server.types.data.ServerState;
import server.utils.Utils;

class ServerServiceAutoHostOfficialTest {

    @TempDir
    Path tempDir;

    private Path base;
    private TestNodes nodes;
    private TestGatewayService gatewayService;
    private TestServerService service;

    static class TestNodes implements NodeManager {
        final Path base;
        final Set<UUID> running = new HashSet<>();

        TestNodes(Path base) {
            this.base = base;
        }

        @Override
        public List<ServerState> list() {
            List<ServerState> states = new ArrayList<>();
            for (UUID id : running) {
                states.add(new ServerState()
                        .running(true)
                        .meta(Optional.of(new ServerMetadata().setConfig(new ServerConfig().setId(id)))));
            }
            return states;
        }

        @Override
        public void create(ServerConfig config) {
        }

        @Override
        public boolean remove(UUID id, NodeRemoveReason reason) {
            return true;
        }

        @Override
        public List<ServerMisMatch> getMismatch(UUID id, ServerConfig config, ServerSnapshot state, List<Mod> mods) {
            return Collections.emptyList();
        }

        @Override
        public Closeable getNodeUsage(UUID serverId, Consumer<NodeUsage> onUsage, Consumer<Throwable> onError) {
            return () -> {};
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
            return running.contains(serverId);
        }

        @Override
        public void onKilled(Consumer<UUID> onKilled) {
        }
    }

    static class TestGatewayService extends GatewayService {
        final Set<UUID> hostingServers = new HashSet<>();

        TestGatewayService(EventBus eventBus, EnvConfig envConfig, NodeManager nodeManager,
                TranslationService translationService, PluginBundleService pluginBundle) {
            super(eventBus, envConfig, nodeManager, translationService, pluginBundle);
        }

        @Override
        public boolean isHosting(UUID serverId) {
            return hostingServers.contains(serverId);
        }
    }

    static class TestServerService extends ServerService {
        final List<UUID> hostedIds = new CopyOnWriteArrayList<>();
        final Set<UUID> failOnHostIds = new HashSet<>();

        TestServerService(GatewayService gatewayService, NodeManager nodeManager, EventBus eventBus,
                ApiService apiService, WsHandler wsHandler, PluginBundleService pluginBundle) {
            super(gatewayService, nodeManager, eventBus, apiService, wsHandler, pluginBundle);
        }

        @Override
        public void host(ServerConfig request) {
            if (failOnHostIds.contains(request.getId())) {
                throw new RuntimeException("Simulated host failure for " + request.getId());
            }
            hostedIds.add(request.getId());
        }
    }

    @BeforeEach
    void setUp() {
        base = tempDir.resolve("servers");
        nodes = new TestNodes(base);
        EnvConfig env = new EnvConfig(
                new EnvConfig.DockerEnv("image", "/data", null, null),
                new EnvConfig.ServerConfig(true, "token", "/data", "ws://localhost/gateway", "http://localhost/"),
                "test-signing-key");
        gatewayService = new TestGatewayService(new EventBus(), env, nodes,
                new TranslationService(Caffeine.newBuilder().build()),
                new PluginBundleService(new byte[] { 1 }, "test"));
        WsHandler wsHandler = new WsHandler(gatewayService, nodes, "test-signing-key");
        service = new TestServerService(gatewayService, nodes, new EventBus(), new ApiService(), wsHandler,
                new PluginBundleService(new byte[] { 1 }, "test"));
    }

    private void writeServerConfig(UUID id, boolean isOfficial, boolean isAutoTurnOff) {
        ServerConfig config = new ServerConfig()
                .setId(id)
                .setName("Server-" + id)
                .setDescription("desc")
                .setMode("survival")
                .setGamemode("survival")
                .setPort(6567)
                .setHostCommand("host")
                .setIsOfficial(isOfficial)
                .setIsAutoTurnOff(isAutoTurnOff);

        ServerConfigMessage msg = new ServerConfigMessage()
                .setJwt("jwt")
                .setConfig(config)
                .setStartServer(new StartServer().setMode("survival").setHostCommand("host"));

        try {
            nodes.writeFile(id, "server.json", Utils.objectMapper.writeValueAsBytes(msg));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void filterOfficialPersistentServersCorrectly() {
        UUID officialPersistent = UUID.randomUUID();
        UUID officialAutoOff = UUID.randomUUID();
        UUID regularPersistent = UUID.randomUUID();
        UUID regularAutoOff = UUID.randomUUID();

        writeServerConfig(officialPersistent, true, false);
        writeServerConfig(officialAutoOff, true, true);
        writeServerConfig(regularPersistent, false, false);
        writeServerConfig(regularAutoOff, false, true);

        // Non-UUID directory
        Fi invalidDir = new Fi(base.resolve("not-a-uuid").toFile());
        invalidDir.mkdirs();

        List<ServerConfig> officialServers = service.findOfficialPersistentServers();

        assertEquals(1, officialServers.size());
        assertEquals(officialPersistent, officialServers.get(0).getId());
        assertTrue(officialServers.get(0).getIsOfficial());
        assertFalse(officialServers.get(0).getIsAutoTurnOff());
    }

    @Test
    void emptyOrMissingFolderReturnsEmptyList() {
        // base folder does not exist yet
        List<ServerConfig> empty = service.findOfficialPersistentServers();
        assertTrue(empty.isEmpty());
    }

    @Test
    void autoHostSkipsAlreadyHostingServers() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        writeServerConfig(id1, true, false);
        writeServerConfig(id2, true, false);

        gatewayService.hostingServers.add(id1);

        service.autoHostOfficialServers();

        assertEquals(1, service.hostedIds.size());
        assertEquals(id2, service.hostedIds.get(0));
    }

    @Test
    void autoHostIsolatesIndividualHostFailures() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        UUID id3 = UUID.randomUUID();

        writeServerConfig(id1, true, false);
        writeServerConfig(id2, true, false);
        writeServerConfig(id3, true, false);

        service.failOnHostIds.add(id2);

        service.autoHostOfficialServers();

        assertEquals(2, service.hostedIds.size());
        assertTrue(service.hostedIds.contains(id1));
        assertTrue(service.hostedIds.contains(id3));
        assertFalse(service.hostedIds.contains(id2));
    }
}
