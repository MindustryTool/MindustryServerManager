package server.service;

import static org.junit.jupiter.api.Assertions.*;

import java.io.Closeable;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.benmanes.caffeine.cache.Caffeine;

import arc.files.Fi;
import common.content.ManagerMap;
import common.content.ManagerMod;
import common.content.MapMetadata;
import common.content.Mod;
import common.network.NodeRemoveReason;
import common.server.ServerConfig;
import common.server.ServerConfigMessage;
import common.server.ServerSnapshot;
import gateway.rpc.RpcChannel;
import gateway.session.WsSession;
import server.EnvConfig;
import server.manager.NodeManager;
import server.service.translation.TranslationService;
import server.types.data.NodeUsage;
import server.types.data.ServerMisMatch;
import server.types.data.ServerState;
import server.utils.Utils;

class ServerServiceUpdateConfigTest {

    @TempDir
    Path tempDir;

    private Path base;
    private FakeNodes nodes;
    private ServerService serverService;
    private GatewayService gatewayService;
    private WsHandler wsHandler;

    static class FakeNodes implements NodeManager {
        final Path base;
        final List<UUID> removed = new CopyOnWriteArrayList<>();
        int createCalls = 0;

        FakeNodes(Path base) {
            this.base = base;
        }

        @Override
        public List<ServerState> list() {
            return Collections.emptyList();
        }

        @Override
        public void create(ServerConfig config) {
            createCalls++;
        }

        @Override
        public boolean remove(UUID id, NodeRemoveReason reason) {
            removed.add(id);
            return true;
        }

        @Override
        public List<ServerMisMatch> getMismatch(UUID id, ServerConfig config, ServerSnapshot state, List<Mod> mods) {
            return Collections.emptyList();
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
            if (!file.exists()) {
                return false;
            }
            return file.delete();
        }

        @Override
        public boolean isRunning(UUID serverId) {
            return true;
        }

        @Override
        public void onKilled(Consumer<UUID> onKilled) {
        }
    }

    static class Loopback implements WsSession {
        RpcChannel peer;
        volatile boolean open = true;

        @Override
        public void sendText(String text) {
            RpcChannel p = peer;
            if (p != null) {
                p.onTextMessage(p.current(), text);
            }
        }

        @Override
        public void sendBinary(ByteBuffer data) {
        }

        @Override
        public void close(int code, String reason) {
            open = false;
        }

        @Override
        public boolean isOpen() {
            return open;
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
        gatewayService = new GatewayService(new EventBus(), env, nodes,
                new TranslationService(Caffeine.newBuilder().build()),
                new PluginBundleService(new byte[] { 1 }, "test"));
        wsHandler = new WsHandler(gatewayService, nodes, "test-signing-key");
        serverService = new ServerService(gatewayService, nodes, new EventBus(), new ApiService(), wsHandler,
                new PluginBundleService(new byte[] { 1 }, "test"));
    }

    private ServerConfig config(UUID serverId, String hostCommand, String mode) {
        return new ServerConfig()
                .setId(serverId)
                .setName("test")
                .setDescription("")
                .setMode(mode)
                .setGamemode(mode)
                .setPort(6567)
                .setEnv(Map.of())
                .setImage("image")
                .setHostCommand(hostCommand)
                .setIsHub(false)
                .setIsAutoTurnOff(true)
                .setIsDefault(false)
                .setIsOfficial(false)
                .setCpu(1f)
                .setMemory(512);
    }

    private ServerConfigMessage readStored(UUID serverId) throws Exception {
        Fi file = nodes.getFile(serverId, "server.json");
        assertTrue(file.exists(), "server.json should exist");
        return Utils.objectMapper.readValue(file.readBytes(), ServerConfigMessage.class);
    }

    @Test
    void updateConfigWritesDiskWithNoProcessChange() throws Exception {
        UUID serverId = UUID.randomUUID();

        serverService.updateConfig(serverId, config(serverId, "host cmd one", "sandbox"));

        ServerConfigMessage stored = readStored(serverId);
        assertNotNull(stored.getJwt(), "jwt preserved or generated");
        assertNotNull(stored.getStartServer());
        assertEquals("host cmd one", stored.getStartServer().getHostCommand());
        assertEquals("sandbox", stored.getStartServer().getMode());

        assertTrue(nodes.isRunning(serverId), "running state untouched");
        assertTrue(nodes.removed.isEmpty(), "no remove triggered");
        assertEquals(0, nodes.createCalls, "no container create triggered");

        String firstJwt = stored.getJwt();

        serverService.updateConfig(serverId, config(serverId, "host cmd two", "survival"));

        ServerConfigMessage updated = readStored(serverId);
        assertEquals(firstJwt, updated.getJwt(), "jwt preserved across updates");
        assertEquals("host cmd two", updated.getStartServer().getHostCommand());
        assertEquals("survival", updated.getStartServer().getMode());
        assertTrue(nodes.removed.isEmpty());
        assertEquals(0, nodes.createCalls);
    }

    @Test
    void wipedStoreHealsOnMismatchWithNoError() throws Exception {
        UUID serverId = UUID.randomUUID();

        serverService.updateConfig(serverId, config(serverId, "cmd-a", "sandbox"));
        assertTrue(nodes.getFile(serverId, "server.json").exists());

        assertTrue(nodes.getFile(serverId, "server.json").delete(), "wipe the store");

        List<ServerMisMatch> result = serverService.getMismatch(serverId, config(serverId, "cmd-b", "survival"));

        assertNotNull(result, "mismatch diff still runs with no store");
        ServerConfigMessage healed = readStored(serverId);
        assertEquals("cmd-b", healed.getStartServer().getHostCommand());
        assertEquals("survival", healed.getStartServer().getMode());
    }

    @Test
    void malformedUpdateConfigFailsLoudAndStoresNothing() throws Exception {
        UUID serverId = UUID.randomUUID();
        serverService.updateConfig(serverId, config(serverId, "good", "sandbox"));
        byte[] before = nodes.getFile(serverId, "server.json").readBytes();

        assertThrows(Exception.class, () -> serverService.updateConfig(serverId, null),
                "null config must fail loud");

        assertArrayEquals(before, nodes.getFile(serverId, "server.json").readBytes(),
                "previous stored config untouched on validation failure");

        assertThrows(Exception.class,
                () -> new BackendRpc.UpdateConfigRequest(serverId, null),
                "dedicated request must reject missing config");
        try {
            new BackendRpc.UpdateConfigRequest(serverId, null);
            fail("expected NullPointerException");
        } catch (NullPointerException e) {
            assertTrue(e.getMessage().contains("UpdateConfigRequest.config"),
                    "error names the concrete class, got: " + e.getMessage());
        }
    }

    @Test
    void backendRpcDispatchesUpdateConfigOverWire() throws Exception {
        BackendRpc backendRpc = new BackendRpc(serverService, gatewayService, nodes);
        RpcChannel serverChannel = RpcChannel.create();
        backendRpc.attach(serverChannel);
        assertTrue(serverChannel.hasHandler("update-config"), "update-config handler registered");

        RpcChannel clientChannel = RpcChannel.create();
        Loopback clientSide = new Loopback();
        Loopback serverSide = new Loopback();
        clientSide.peer = serverChannel;
        serverSide.peer = clientChannel;
        clientChannel.onOpen(clientSide);
        serverChannel.onOpen(serverSide);

        try {
            UUID serverId = UUID.randomUUID();
            Map<String, Object> payload = Map.of(
                    "serverId", serverId.toString(),
                    "config", Utils.objectMapper.convertValue(
                            config(serverId, "wire-cmd", "sandbox"), Map.class));

            CompletableFuture<JsonNode> ok = clientChannel.sendRequest("update-config", payload, JsonNode.class,
                    Duration.ofSeconds(5));
            ok.get(5, TimeUnit.SECONDS);

            ServerConfigMessage stored = readStored(serverId);
            assertEquals("wire-cmd", stored.getStartServer().getHostCommand());

            byte[] before = nodes.getFile(serverId, "server.json").readBytes();
            Map<String, Object> badPayload = Map.of("serverId", serverId.toString());
            CompletableFuture<JsonNode> bad = clientChannel.sendRequest("update-config", badPayload, JsonNode.class,
                    Duration.ofSeconds(5));
            try {
                bad.get(5, TimeUnit.SECONDS);
                fail("malformed payload must return response-error");
            } catch (Exception e) {
                assertNotNull(e.getCause() != null ? e.getCause() : e);
            }
            assertArrayEquals(before, nodes.getFile(serverId, "server.json").readBytes(),
                    "malformed wire payload stores nothing");
        } finally {
            clientChannel.shutdown();
            serverChannel.shutdown();
        }
    }

    @Test
    void updateConfigRequestShapeMatchesMismatchShape() {
        UUID serverId = UUID.randomUUID();
        ServerConfig cfg = config(serverId, "cmd", "sandbox");
        var update = new BackendRpc.UpdateConfigRequest(serverId, cfg);
        var mismatch = new BackendRpc.MismatchRequest(serverId, cfg);
        assertEquals(mismatch.serverId(), update.serverId());
        assertEquals(mismatch.config().getHostCommand(), update.config().getHostCommand());
        assertEquals(mismatch.config().getMode(), update.config().getMode());
    }
}
