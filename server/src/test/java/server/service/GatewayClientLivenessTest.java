package server.service;

import java.io.Closeable;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.benmanes.caffeine.cache.Caffeine;

import arc.files.Fi;
import dto.ManagerMapDto;
import dto.ManagerModDto;
import dto.MapDto;
import dto.ModDto;
import dto.ServerConfig;
import dto.ServerStateDto;
import enums.NodeRemoveReason;
import events.BaseEvent;
import events.ServerEvents.LogEvent;
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
        public List<ServerMisMatch> getMismatch(UUID id, ServerConfig config, ServerStateDto state, List<ModDto> mods) {
            return Collections.emptyList();
        }

        @Override
        public Closeable getNodeUsage(UUID serverId, Consumer<NodeUsage> onUsage, Consumer<Throwable> onError) {
            return () -> {
            };
        }

        @Override
        public List<ManagerMapDto> getManagerMaps() {
            return Collections.emptyList();
        }

        @Override
        public List<ManagerModDto> getManagerMods() {
            return Collections.emptyList();
        }

        @Override
        public List<MapDto> getMaps(UUID serverId) {
            return Collections.emptyList();
        }

        @Override
        public List<ModDto> getMods(UUID serverId) {
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

    static class FakeSession implements WsSession {
        private final boolean open;

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
        bus = new EventBus();
        events = new CopyOnWriteArrayList<>();
        bus.on(events::add);
        EnvConfig env = new EnvConfig(
                new EnvConfig.DockerEnv("image", "/data", null, null),
                new EnvConfig.ServerConfig(true, "token", "/data", "ws://localhost/gateway", "http://localhost/"));
        TranslationService translations = new TranslationService(Caffeine.newBuilder().build());
        PluginBundleService bundles = new PluginBundleService(new byte[] { 1 }, "test");
        service = new GatewayService(bus, env, new StubNodeManager(), translations, bundles);
    }

    private GatewayService.GatewayClient client() {
        return service.of(UUID.randomUUID());
    }

    private Instant disconnectAt(GatewayService.GatewayClient client) throws Exception {
        Field field = client.getClass().getDeclaredField("lastDisconnectAt");
        field.setAccessible(true);
        return (Instant) field.get(client);
    }

    // Javalin connect context is not available in unit tests, so post-open state
    // (cleared clock plus open channel) is arranged directly.
    private void emulateOpen(GatewayService.GatewayClient client) throws Exception {
        client.rpcChannel().onOpen(new FakeSession(true));
        Field field = client.getClass().getDeclaredField("lastDisconnectAt");
        field.setAccessible(true);
        field.set(client, null);
    }

    private void ageClock(GatewayService.GatewayClient client, Duration age) throws Exception {
        Field field = client.getClass().getDeclaredField("lastDisconnectAt");
        field.setAccessible(true);
        field.set(client, Instant.now().minus(age));
    }

    private long warnCount() {
        return events.stream().filter(LogEvent.class::isInstance).count();
    }

    @Test
    void initClockEqualsCreateTime() throws Exception {
        GatewayService.GatewayClient client = client();
        assertEquals(client.createdAt, disconnectAt(client));
    }

    @Test
    void closeStartsClock() throws Exception {
        GatewayService.GatewayClient client = client();
        emulateOpen(client);
        assertNull(disconnectAt(client));

        client.onClose(null);

        Instant disconnected = disconnectAt(client);
        assertNotNull(disconnected);
        assertFalse(Instant.now().plusSeconds(5).isBefore(disconnected));
    }

    @Test
    void openSocketNeverWarnsOrTerminates() throws Exception {
        GatewayService.GatewayClient client = client();
        emulateOpen(client);

        client.checkDisconnect();

        assertEquals(0, warnCount());
        assertFalse(client.shouldTerminate());
    }

    @Test
    void closedSocketWarnsAt60sAndTerminatesAt3min() throws Exception {
        GatewayService.GatewayClient freshWarn = client();
        ageClock(freshWarn, Duration.ofSeconds(61));

        freshWarn.checkDisconnect();

        assertEquals(1, warnCount());
        assertFalse(freshWarn.shouldTerminate());

        GatewayService.GatewayClient freshKill = client();
        ageClock(freshKill, Duration.ofMinutes(3).plusSeconds(1));

        assertTrue(freshKill.shouldTerminate());
    }

    @Test
    void freshDisconnectStaysQuiet() throws Exception {
        GatewayService.GatewayClient client = client();
        ageClock(client, Duration.ofSeconds(10));

        client.checkDisconnect();

        assertEquals(0, warnCount());
        assertFalse(client.shouldTerminate());
    }

    @Test
    void reconnectGrantsFreshGrace() throws Exception {
        GatewayService.GatewayClient client = client();
        ageClock(client, Duration.ofMinutes(10));
        assertTrue(client.shouldTerminate());

        emulateOpen(client);

        client.checkDisconnect();

        assertEquals(0, warnCount());
        assertFalse(client.shouldTerminate());
    }
}
