package server.service;

import java.io.Closeable;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.benmanes.caffeine.cache.Caffeine;

import arc.files.Fi;
import common.content.ManagerMap;
import common.content.ManagerMod;
import common.content.MapMetadata;
import common.content.Mod;
import common.server.ServerConfig;
import common.server.ServerMetadata;
import common.server.ServerSnapshot;
import common.network.NodeRemoveReason;
import common.event.BaseEvent;
import common.event.ServerEvents.StopEvent;
import gateway.session.WsSession;
import server.EnvConfig;
import server.manager.NodeManager;
import server.service.translation.TranslationService;
import server.types.data.NodeUsage;
import server.types.data.ServerMisMatch;
import server.types.data.ServerState;

import static org.junit.jupiter.api.Assertions.*;

public class GatewayClientLivenessTest {

    static class StubNodeManager implements NodeManager {
        @Override
        public List<ServerState> list() {
            return Collections.emptyList();
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
            return null;
        }

        @Override
        public Fi getFile(UUID serverId, String path) {
            return null;
        }

        @Override
        public Fi getServerFolder() {
            return null;
        }

        @Override
        public void writeFile(UUID serverId, String path, byte[] data) {
        }

        @Override
        public boolean createFolder(UUID serverId, String path) {
            return false;
        }

        @Override
        public boolean deleteFile(UUID serverId, String path) {
            return false;
        }

        @Override
        public boolean isRunning(UUID serverId) {
            return true;
        }

        @Override
        public void onKilled(Consumer<UUID> onKilled) {
        }
    }

    /** Node manager with a configurable running set and call/removal recording. */
    static class RunningNodeManager extends StubNodeManager {
        private final Set<UUID> running = new HashSet<>();
        final List<UUID> removed = new CopyOnWriteArrayList<>();
        int listCalls = 0;

        void setRunning(UUID... ids) {
            running.clear();
            running.addAll(List.of(ids));
        }

        @Override
        public List<ServerState> list() {
            listCalls++;
            List<ServerState> states = new ArrayList<>();
            for (UUID id : running) {
                states.add(new ServerState()
                        .running(true)
                        .meta(Optional.of(new ServerMetadata().setConfig(new ServerConfig().setId(id)))));
            }
            return states;
        }

        @Override
        public boolean remove(UUID id, NodeRemoveReason reason) {
            removed.add(id);
            return true;
        }
    }

    static class FakeSession implements WsSession {
        private final boolean open;
        volatile int closeCode = -1;
        volatile String closeReason;

        FakeSession(boolean open) {
            this.open = open;
        }

        @Override
        public void sendText(String text) {
        }

        @Override
        public void sendBinary(ByteBuffer data) {
        }

        @Override
        public void close(int code, String reason) {
            this.closeCode = code;
            this.closeReason = reason;
        }

        @Override
        public boolean isOpen() {
            return open;
        }
    }

    private GatewayService service;
    private EventBus bus;
    private List<BaseEvent> events;

    @BeforeEach
    void setUp() {
        setUp(new StubNodeManager());
    }

    private void setUp(NodeManager nodes) {
        bus = new EventBus();
        events = new CopyOnWriteArrayList<>();
        bus.on(events::add);
        EnvConfig env = new EnvConfig(
                new EnvConfig.DockerEnv("image", "/data", null, null),
                new EnvConfig.ServerConfig(true, "token", "/data", "ws://localhost/gateway", "http://localhost/"),
                "test-signing-key");
        TranslationService translations = new TranslationService(Caffeine.newBuilder().build());
        PluginBundleService bundles = new PluginBundleService(new byte[] { 1 }, "test");
        service = new GatewayService(bus, env, nodes, translations, bundles);
    }

    private GatewayService.GatewayClient client() {
        return service.of(UUID.randomUUID());
    }

    private long stopCount() {
        return events.stream().filter(StopEvent.class::isInstance).count();
    }

    // ------------------------------------------------------------------
    // Reusable client handle
    // ------------------------------------------------------------------

    @Test
    void openAdoptsSessionOnCachedHandle() {
        GatewayService.GatewayClient handle = client();
        handle.rpcChannel().onOpen(new FakeSession(true));

        assertNotNull(handle.rpcChannel().current(), "open adopts the incoming session");
    }

    @Test
    void cacheMissBuildsReusableHandle() {
        UUID id = UUID.randomUUID();
        GatewayService.GatewayClient first = service.of(id);

        assertSame(first, service.of(id), "of() returns the cached handle");
    }

    // ------------------------------------------------------------------
    // Batched eviction sweep
    // ------------------------------------------------------------------

    @Test
    void sweepEvictsClosedSocketWithContainerGone() throws Exception {
        RunningNodeManager nodes = new RunningNodeManager();
        setUp(nodes);

        UUID id = UUID.randomUUID();
        GatewayService.GatewayClient before = service.of(id);
        service.of(UUID.randomUUID());

        service.sweep();

        GatewayService.GatewayClient after = service.of(id);
        assertNotSame(before, after, "container-gone client must be evicted from the cache");
        assertTrue(nodes.removed.isEmpty(), "eviction must not terminate or remove a container");
        assertEquals(0, stopCount());
    }

    @Test
    void sweepKeepsClosedSocketWithRunningContainer() throws Exception {
        RunningNodeManager nodes = new RunningNodeManager();
        UUID id = UUID.randomUUID();
        nodes.setRunning(id);
        setUp(nodes);

        GatewayService.GatewayClient before = service.of(id);

        service.sweep();

        assertSame(before, service.of(id), "running container keeps the handle");
        assertTrue(nodes.removed.isEmpty(), "sweep must never remove a container");
        assertEquals(0, stopCount());
    }

    @Test
    void sweepNeverRemovesContainer() throws Exception {
        RunningNodeManager nodes = new RunningNodeManager();
        UUID id = UUID.randomUUID();
        nodes.setRunning(id);
        setUp(nodes);

        service.of(id).rpcChannel().onOpen(new FakeSession(true));
        service.sweep();

        assertTrue(nodes.removed.isEmpty(), "a live container is never removed by the sweep");
        assertEquals(0, stopCount());
    }

    @Test
    void sweepListsContainersOncePerCycle() throws Exception {
        RunningNodeManager nodes = new RunningNodeManager();
        setUp(nodes);

        service.of(UUID.randomUUID());
        service.of(UUID.randomUUID());
        service.of(UUID.randomUUID());

        service.sweep();

        assertEquals(1, nodes.listCalls, "one container list serves the whole sweep");
    }

    // ------------------------------------------------------------------
    // Termination
    // ------------------------------------------------------------------

    @Test
    void terminateCloses4234RemovesContainerEmitsReasonAndKeepsEntry() throws Exception {
        RunningNodeManager nodes = new RunningNodeManager();
        setUp(nodes);

        UUID id = UUID.randomUUID();
        GatewayService.GatewayClient client = service.of(id);
        FakeSession session = new FakeSession(true);
        client.rpcChannel().onOpen(session);

        boolean result = service.terminate(id, NodeRemoveReason.NO_PLAYER);

        assertTrue(result);
        assertEquals(4234, session.closeCode, "termination close must use the terminal code");
        assertEquals(List.of(id), nodes.removed);
        assertEquals(1, stopCount());
        assertEquals(NodeRemoveReason.NO_PLAYER.name(),
                ((StopEvent) events.stream().filter(StopEvent.class::isInstance).findFirst().orElseThrow()).getReason());
        assertSame(client, service.of(id), "terminate must not drop the cache entry");
    }
}
