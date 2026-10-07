package server.service;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.github.benmanes.caffeine.cache.Caffeine;

import arc.files.Fi;
import gateway.client.WsClient;
import gateway.rpc.RpcChannel;
import io.javalin.Javalin;
import io.javalin.config.JavalinConfig;
import io.javalin.json.JavalinJackson;
import server.EnvConfig;
import server.manager.NodeManager;
import server.service.translation.TranslationService;

/**
 * Local Jetty-to-JDK loopback probe: a real Javalin gateway plus real
 * {@link WsClient} instances over loopback. Covers the original wedge
 * (dirty reconnect must not throw {@code Duplicate onOpen}) and the
 * {@code 4234} kick passthrough (Jetty must deliver the app-private code
 * and the kicked client must stay down).
 */
class GatewayLoopbackProbeTest {

    private Javalin app;
    private int port;
    private GatewayService service;
    private WsHandler wsHandler;

    private static final String TEST_SIGNING_KEY = "test-signing-key";

    @BeforeEach
    void setUp() throws Exception {
        EnvConfig env = new EnvConfig(
                new EnvConfig.DockerEnv("image", "/data", null, null),
                new EnvConfig.ServerConfig(true, "token", "/data", "ws://localhost/gateway", "http://localhost/"),
                TEST_SIGNING_KEY);
        TranslationService translations = new TranslationService(Caffeine.newBuilder().build());
        PluginBundleService bundles = new PluginBundleService(new byte[] { 1 }, "test");
        service = new GatewayService(
                new EventBus(), env, new GatewayClientLivenessTest.StubNodeManager(), translations, bundles);
        wsHandler = new WsHandler(
                service, new GatewayClientLivenessTest.StubNodeManager(), TEST_SIGNING_KEY);

        port = freePort();
        app = Javalin.create(config -> {
            config.showJavalinBanner = false;
            config.router.contextPath = "/";
            config.jetty.modifyWebSocketServletFactory(factory -> {
                factory.setMaxTextMessageSize(50 * 1024 * 1024);
                factory.setMaxBinaryMessageSize(50 * 1024 * 1024);
            });
            config.jsonMapper(new JavalinJackson().updateMapper(mapper -> {
                mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
            }));
        });
        app.ws("/gateway", wsHandler::configure);
        app.start(port);
    }

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
    }

    @Test
    void duplicateOpenKicksFirstClientWith4234() throws Exception {
        UUID serverId = UUID.randomUUID();
        String token = wsHandler.generateServerJwt(serverId);

        RpcChannel channelA = RpcChannel.create();
        WsClient a = clientFor(channelA, serverId, token);
        try {
            a.connect();
            awaitCondition(a::isOpen, "first client open");
            assertEquals("test", pluginVersion(channelA));

            // Second connection, same id, first socket left open: must not wedge.
            RpcChannel channelB = RpcChannel.create();
            WsClient b = clientFor(channelB, serverId, token);
            try {
                b.connect();
                awaitCondition(b::isOpen, "second client open");

                // First client receives the 4234 kick over the real wire.
                awaitCondition(() -> !a.isOpen(), "first client kicked");
                Thread.sleep(2500);
                assertFalse(a.isOpen(), "kicked client stays down past min backoff");
                assertEquals(0, a.getReconnectAttempt(), "kick must not schedule reconnect");
                assertNull(channelA.current());

                // Second client adopted: requests flow.
                assertEquals("test", pluginVersion(channelB));

                // Server clock clean: overwrite cleared it, stale close ignored.
                assertNull(disconnectAt(service.of(serverId)));
            } finally {
                b.close();
                channelB.shutdown();
            }
        } finally {
            a.close();
            channelA.shutdown();
        }
    }

    @Test
    void dirtyTcpDeathHealsOnReconnect() throws Exception {
        UUID serverId = UUID.randomUUID();
        String token = wsHandler.generateServerJwt(serverId);

        RpcChannel channel = RpcChannel.create();
        WsClient client = clientFor(channel, serverId, token);
        try {
            client.connect();
            awaitCondition(client::isOpen, "client open");
            assertEquals("test", pluginVersion(channel));

            // RST with no close frame and no local callback: the pong deadline
            // is the only detector, then auto redial must heal.
            abortSocket(client);

            awaitCondition(() -> !client.isOpen(), "drop detected via pong deadline");
            awaitCondition(client::isOpen, "client reconnected");
            assertEquals("test", pluginVersion(channel));
            assertNotNull(service.of(serverId).rpcChannel().current());
        } finally {
            client.close();
            channel.shutdown();
        }
    }

    /**
     * One client channel, two sender threads: two streams pushed to the
     * server at the same time. Each stream must arrive complete and
     * uncorrupted, with no cross-talk between the interleaved chunks.
     */
    @Test
    void clientSendsTwoConcurrentStreamsIntact() throws Exception {
        UUID serverId = UUID.randomUUID();
        String token = wsHandler.generateServerJwt(serverId);

        byte[] alpha = filled(3 * 1024 * 1024 + 7, (byte) 0xA1);
        byte[] beta = filled(2 * 1024 * 1024 + 13, (byte) 0xB2);

        Map<String, byte[]> received = new ConcurrentHashMap<>();
        RpcChannel channel = RpcChannel.create();
        WsClient client = clientFor(channel, serverId, token);
        ExecutorService senders = Executors.newFixedThreadPool(2);
        try {
            client.connect();
            awaitCondition(client::isOpen, "client open");

            RpcChannel server = service.of(serverId).rpcChannel();
            server.registerStreamHandler("alpha", String.class, String.class,
                    (meta, bytes) -> {
                        received.put("alpha", bytes);
                        return "ok";
                    });
            server.registerStreamHandler("beta", String.class, String.class,
                    (meta, bytes) -> {
                        received.put("beta", bytes);
                        return "ok";
                    });

            CountDownLatch go = new CountDownLatch(1);
            CompletableFuture<String> a = gatedSend(senders, go, channel, "alpha", alpha);
            CompletableFuture<String> b = gatedSend(senders, go, channel, "beta", beta);
            go.countDown();

            assertEquals("ok", a.get(30, TimeUnit.SECONDS));
            assertEquals("ok", b.get(30, TimeUnit.SECONDS));
            assertArrayEquals(alpha, received.get("alpha"), "alpha stream intact on server");
            assertArrayEquals(beta, received.get("beta"), "beta stream intact on server");
        } finally {
            senders.shutdownNow();
            client.close();
            channel.shutdown();
        }
    }

    /**
     * One client receiving from one server: the server pushes two streams at
     * the same time over the single socket. Both must reassemble intact.
     */
    @Test
    void clientReceivesTwoConcurrentStreamsIntact() throws Exception {
        UUID serverId = UUID.randomUUID();
        String token = wsHandler.generateServerJwt(serverId);

        byte[] alpha = filled(2 * 1024 * 1024 + 11, (byte) 0xC3);
        byte[] beta = filled(3 * 1024 * 1024 + 5, (byte) 0xD4);

        Map<String, byte[]> received = new ConcurrentHashMap<>();
        RpcChannel channel = RpcChannel.create();
        channel.registerStreamHandler("alpha", String.class, String.class,
                (meta, bytes) -> {
                    received.put("alpha", bytes);
                    return "ok";
                });
        channel.registerStreamHandler("beta", String.class, String.class,
                (meta, bytes) -> {
                    received.put("beta", bytes);
                    return "ok";
                });

        WsClient client = clientFor(channel, serverId, token);
        ExecutorService senders = Executors.newFixedThreadPool(2);
        try {
            client.connect();
            awaitCondition(client::isOpen, "client open");

            RpcChannel server = service.of(serverId).rpcChannel();

            CountDownLatch go = new CountDownLatch(1);
            CompletableFuture<String> a = gatedSend(senders, go, server, "alpha", alpha);
            CompletableFuture<String> b = gatedSend(senders, go, server, "beta", beta);
            go.countDown();

            assertEquals("ok", a.get(30, TimeUnit.SECONDS));
            assertEquals("ok", b.get(30, TimeUnit.SECONDS));
            assertArrayEquals(alpha, received.get("alpha"), "alpha stream intact on client");
            assertArrayEquals(beta, received.get("beta"), "beta stream intact on client");
        } finally {
            senders.shutdownNow();
            client.close();
            channel.shutdown();
        }
    }

    /**
     * Watchtower update: the manager container is removed and recreated.
     * The recreated manager reuses the same required env signing key, so
     * the token written to the shared volume stays valid. The client must
     * ride out the outage on backoff and heal with exactly one live
     * session and no reconnect storm.
     */
    @Test
    void managerRecreatedDuringOutageReconnectsCleanly(@TempDir Path volume) throws Exception {
        UUID serverId = UUID.randomUUID();
        VolumeNodeManager volumes = new VolumeNodeManager(volume);

        RpcChannel channel = RpcChannel.create();
        WsClient client = null;
        try {
            // Generation 1: provisioned manager with a shared volume.
            GatewayService service1 = newService(volumes);
            WsHandler handler1 = new WsHandler(service1, volumes, TEST_SIGNING_KEY);
            startApp(handler1);
            String firstToken = handler1.generateServerJwt(serverId);
            writeServerJson(volumes, serverId, firstToken);

            ObjectMapper mapper = new ObjectMapper();
            client = WsClient
                    .builder(URI.create("ws://localhost:" + port + "/gateway"), channel)
                    .headersSupplier(() -> readGatewayHeaders(mapper, volumes, serverId))
                    .pingInterval(Duration.ofMillis(200))
                    .pongDeadline(Duration.ofSeconds(2))
                    .build();
            client.connect();
            final WsClient connected = client;
            awaitCondition(connected::isOpen, "client open on generation 1");
            assertEquals("test", pluginVersion(channel));

            // Watchtower removes the manager: connections die, state is gone.
            app.stop();

            // Generation 2: fresh service and channel state, same env signing
            // key, same port and volume.
            GatewayService service2 = newService(volumes);
            startApp(new WsHandler(service2, volumes, TEST_SIGNING_KEY));

            // Redials fail while the port is dead, then the same token is
            // accepted: the client must heal on its own.
            awaitCondition(connected::isOpen, "reconnected to recreated manager");
            assertEquals("test", pluginVersion(channel));

            // Exactly one live session, clean clock, no pending backoff.
            assertNotNull(service2.of(serverId).rpcChannel().current());
            assertNull(disconnectAt(service2.of(serverId)));
            assertEquals(0, connected.getReconnectAttempt());

            // Stable key: the token in the shared volume is unchanged.
            assertEquals(firstToken, readJwt(mapper, volumes, serverId));
        } finally {
            if (client != null) {
                client.close();
            }
            channel.shutdown();
        }
    }

    private static class VolumeNodeManager extends GatewayClientLivenessTest.StubNodeManager {
        private final Path base;

        VolumeNodeManager(Path base) {
            this.base = base;
        }

        @Override
        public Fi getFile(UUID serverId, String path) {
            return new Fi(base.resolve(serverId.toString()).resolve(path).toFile());
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
        public Fi getServerFolder() {
            return new Fi(base.toFile());
        }
    }

    private GatewayService newService(NodeManager nodes) {
        EnvConfig env = new EnvConfig(
                new EnvConfig.DockerEnv("image", "/data", null, null),
                new EnvConfig.ServerConfig(true, "token", "/data", "ws://localhost/gateway", "http://localhost/"),
                TEST_SIGNING_KEY);
        TranslationService translations = new TranslationService(Caffeine.newBuilder().build());
        PluginBundleService bundles = new PluginBundleService(new byte[] { 1 }, "test");
        return new GatewayService(new EventBus(), env, nodes, translations, bundles);
    }

    private void startApp(WsHandler handler) {
        if (app != null) {
            app.stop();
            app = null;
        }
        app = Javalin.create(config -> {
            config.showJavalinBanner = false;
            config.router.contextPath = "/";
            config.jetty.modifyWebSocketServletFactory(factory -> {
                factory.setMaxTextMessageSize(50 * 1024 * 1024);
                factory.setMaxBinaryMessageSize(50 * 1024 * 1024);
            });
            appJsonMapper(config);
        });
        app.ws("/gateway", handler::configure);
        app.start(port);
    }

    private void appJsonMapper(JavalinConfig config) {
        config.jsonMapper(new JavalinJackson().updateMapper(mapper -> {
            mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        }));
    }

    private static void writeServerJson(VolumeNodeManager volumes, UUID serverId, String jwt) throws Exception {
        String json = new ObjectMapper().writeValueAsString(Map.of("jwt", jwt));
        volumes.writeFile(serverId, "server.json", json.getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, String> readGatewayHeaders(
            ObjectMapper mapper, VolumeNodeManager volumes, UUID serverId) {
        try {
            Fi file = volumes.getFile(serverId, "server.json");
            String jwt = file.exists()
                    ? mapper.readTree(file.readBytes()).path("jwt").asText(null)
                    : null;
            if (jwt == null || jwt.isBlank()) {
                return Map.of("X-SERVER-ID", serverId.toString());
            }
            return Map.of("Authorization", jwt, "X-SERVER-ID", serverId.toString());
        } catch (Exception e) {
            return Map.of("X-SERVER-ID", serverId.toString());
        }
    }

    private static String readJwt(ObjectMapper mapper, VolumeNodeManager volumes, UUID serverId) throws Exception {
        return mapper.readTree(volumes.getFile(serverId, "server.json").readBytes()).path("jwt").asText();
    }

    private WsClient clientFor(RpcChannel channel, UUID serverId, String token) {
        return WsClient
                .builder(URI.create("ws://localhost:" + port + "/gateway"), channel)
                .headersSupplier(() -> Map.of(
                        "Authorization", token,
                        "X-SERVER-ID", serverId.toString()))
                .pingInterval(Duration.ofMillis(200))
                .pongDeadline(Duration.ofSeconds(2))
                .build();
    }

    private static String pluginVersion(RpcChannel channel) throws Exception {
        return channel
                .sendRequest("get-plugin-version", null, String.class, Duration.ofSeconds(10))
                .get(15, TimeUnit.SECONDS);
    }

    private static byte[] filled(int size, byte value) {
        byte[] data = new byte[size];
        Arrays.fill(data, value);
        return data;
    }

    private static CompletableFuture<String> gatedSend(
            ExecutorService pool, CountDownLatch go, RpcChannel channel, String type, byte[] data) {
        CompletableFuture<String> result = new CompletableFuture<>();
        pool.submit(() -> {
            try {
                go.await(5, TimeUnit.SECONDS);
                result.complete(
                        channel.sendStream(type, "m", data, String.class, Duration.ofSeconds(30))
                                .get(30, TimeUnit.SECONDS));
            } catch (Exception e) {
                result.completeExceptionally(e);
            }
        });
        return result;
    }

    private static void abortSocket(WsClient client) throws Exception {
        Field current = WsClient.class.getDeclaredField("current");
        current.setAccessible(true);
        Object transport = current.get(client);
        assertNotNull(transport, "expected a live transport");
        Field socket = transport.getClass().getDeclaredField("socket");
        socket.setAccessible(true);
        ((WebSocket) socket.get(transport)).abort();
    }

    private static Instant disconnectAt(GatewayService.GatewayClient client) throws Exception {
        Field field = client.getClass().getDeclaredField("lastDisconnectAt");
        field.setAccessible(true);
        return (Instant) field.get(client);
    }

    private static void awaitCondition(Supplier<Boolean> cond, String what) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!cond.get()) {
            if (System.nanoTime() > deadline) {
                fail("Timed out waiting for: " + what);
            }
            Thread.sleep(50);
        }
    }

    private static int freePort() throws Exception {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
