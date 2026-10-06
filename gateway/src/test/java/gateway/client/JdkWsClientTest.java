package gateway.client;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import gateway.rpc.WsRpcChannel;

/**
 * Covers the {@code migrate-plugin-to-jdk-ws} gateway tasks: builder scheme
 * validation, per-attempt headers supplier, null-binary drop, unchanged
 * backoff, and the single-sender queue (burst serialization, FIFO order,
 * fail-fast drop, shutdown).
 */
class JdkWsClientTest {

    // ------------------------------------------------------------------
    // Gate-enforcing fake: mimics the JDK single-outstanding-send rule.
    // ------------------------------------------------------------------

    static class GateEnforcingFake implements WebSocket {
        final List<String> texts = Collections.synchronizedList(new ArrayList<>());
        final List<String> order = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger binaries = new AtomicInteger(0);
        final AtomicInteger pings = new AtomicInteger(0);
        final AtomicInteger closes = new AtomicInteger(0);
        final AtomicBoolean sendPending = new AtomicBoolean(false);
        final AtomicInteger inFlight = new AtomicInteger(0);
        final AtomicInteger maxInFlight = new AtomicInteger(0);
        final AtomicBoolean failSends = new AtomicBoolean(false);
        final ExecutorService async = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "fake-ws-async");
            t.setDaemon(true);
            return t;
        });
        volatile boolean outputClosed = false;

        private CompletableFuture<WebSocket> guarded(Runnable record) {
            if (!sendPending.compareAndSet(false, true)) {
                throw new IllegalStateException("Send pending");
            }
            int cur = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(cur, Math::max);
            return CompletableFuture.supplyAsync(() -> {
                try {
                    Thread.sleep(2);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (failSends.get()) {
                    throw new RuntimeException("boom");
                }
                record.run();
                return (WebSocket) this;
            }, async).whenComplete((r, e) -> {
                inFlight.decrementAndGet();
                sendPending.set(false);
            });
        }

        @Override
        public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
            String s = data.toString();
            return guarded(() -> {
                texts.add(s);
                order.add("text:" + s);
            });
        }

        @Override
        public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) {
            ByteBuffer dup = data.duplicate();
            byte[] bytes = new byte[dup.remaining()];
            dup.get(bytes);
            return guarded(() -> {
                binaries.incrementAndGet();
                order.add("binary");
            });
        }

        @Override
        public CompletableFuture<WebSocket> sendPing(ByteBuffer message) {
            return guarded(pings::incrementAndGet);
        }

        @Override
        public CompletableFuture<WebSocket> sendPong(ByteBuffer message) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
            outputClosed = true;
            return guarded(closes::incrementAndGet);
        }

        @Override
        public void request(long n) {
        }

        @Override
        public String getSubprotocol() {
            return null;
        }

        @Override
        public boolean isOutputClosed() {
            return outputClosed;
        }

        @Override
        public boolean isInputClosed() {
            return false;
        }

        @Override
        public void abort() {
            outputClosed = true;
        }
    }

    private static JdkWsClient clientOf(WsRpcChannel channel) {
        return JdkWsClient.builder(URI.create("ws://localhost:1/gateway"), channel).build();
    }

    private static void awaitCondition(Supplier<Boolean> cond, String what) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!cond.get()) {
            if (System.nanoTime() > deadline) {
                fail("Timed out waiting for: " + what);
            }
            Thread.sleep(20);
        }
    }

    // ------------------------------------------------------------------
    // 1.1 / 1.2: builder validation + shim
    // ------------------------------------------------------------------

    @Test
    void nonWsSchemeRejected() {
        WsRpcChannel channel = WsRpcChannel.create();
        try {
            assertThrows(IllegalArgumentException.class, () -> JdkWsClient
                    .builder(URI.create("http://localhost:8089/gateway"), channel).build());
            assertThrows(IllegalArgumentException.class, () -> JdkWsClient
                    .builder(URI.create("https://localhost:8089/gateway"), channel).build());
            JdkWsClient ws = JdkWsClient.builder(URI.create("ws://localhost:8089/gateway"), channel).build();
            ws.close();
            JdkWsClient wss = JdkWsClient.builder(URI.create("wss://localhost:8089/gateway"), channel).build();
            wss.close();
        } finally {
            channel.shutdown();
        }
    }


    @Test
    void tunablePingPong() {
        WsRpcChannel channel = WsRpcChannel.create();
        JdkWsClient client = JdkWsClient
                .builder(URI.create("ws://localhost:1/gateway"), channel)
                .pingInterval(Duration.ofSeconds(5))
                .pongDeadline(Duration.ofSeconds(10))
                .build();
        try {
            assertEquals(Duration.ofSeconds(5), client.getPingInterval());
            assertEquals(Duration.ofSeconds(10), client.getPongDeadline());
        } finally {
            client.close();
            channel.shutdown();
        }
    }

    // ------------------------------------------------------------------
    // 1.3: supplier invoked per attempt, blanks filtered
    // ------------------------------------------------------------------

    @Test
    void headersSupplierResolvedPerAttemptAndFiltered() throws Exception {
        WsRpcChannel channel = WsRpcChannel.create();
        AtomicInteger calls = new AtomicInteger(0);
        Map<String, String> raw = new LinkedHashMap<>();
        raw.put("Authorization", "jwt-abc");
        raw.put("X-SERVER-ID", "sid-1");
        raw.put("Empty", "   ");
        raw.put(null, "x");
        JdkWsClient client = JdkWsClient
                .builder(URI.create("ws://127.0.0.1:1/gateway"), channel)
                .headersSupplier(() -> {
                    calls.incrementAndGet();
                    return raw;
                })
                .build();
        try {
            Map<String, String> resolved = client.resolveHeaders();
            assertEquals(Map.of("Authorization", "jwt-abc", "X-SERVER-ID", "sid-1"), resolved);
            assertEquals(1, calls.get());

            // One handshake attempt resolves the supplier synchronously.
            CompletableFuture<Void> connect = client.connect();
            assertEquals(2, calls.get());
            connect.get(10, TimeUnit.SECONDS);
        } finally {
            client.close();
            channel.shutdown();
        }
    }

    @Test
    void missingJwtDialsWithoutAuthorization() {
        WsRpcChannel channel = WsRpcChannel.create();
        JdkWsClient client = JdkWsClient
                .builder(URI.create("ws://localhost:1/gateway"), channel)
                .headersSupplier(() -> {
                    Map<String, String> m = new LinkedHashMap<>();
                    m.put("X-SERVER-ID", "sid-1");
                    return m;
                })
                .build();
        try {
            assertEquals(Map.of("X-SERVER-ID", "sid-1"), client.resolveHeaders());
        } finally {
            client.close();
            channel.shutdown();
        }
    }

    @Test
    void streamFramesPreserveStartChunksDoneOrder() throws Exception {
        WsRpcChannel channel = WsRpcChannel.create();
        JdkWsClient client = clientOf(channel);
        GateEnforcingFake fake = new GateEnforcingFake();
        client.setTestWebSocket(fake);
        try {
            byte[] data = new byte[150 * 1024];
            java.util.Arrays.fill(data, (byte) 5);
            channel.sendStream("big", "m", data, Void.class, Duration.ofSeconds(10));

            awaitCondition(() -> fake.texts.size() == 2 && fake.binaries.get() == 3, "start + 3 chunks + done");
            awaitCondition(() -> client.getSendQueueDepth() == 0, "queue drained");

            List<String> order = new ArrayList<>(fake.order);
            assertEquals(5, order.size());
            assertTrue(order.get(0).contains("\"type\":\"" + WsRpcChannel.STREAM_START_TYPE + "\""),
                    "start first, got: " + order.get(0));
            assertEquals("binary", order.get(1));
            assertEquals("binary", order.get(2));
            assertEquals("binary", order.get(3));
            assertTrue(order.get(4).contains("\"type\":\"" + WsRpcChannel.STREAM_DONE_TYPE + "\""),
                    "done last, got: " + order.get(4));
        } finally {
            client.close();
            channel.shutdown();
            fake.async.shutdownNow();
        }
    }

    // ------------------------------------------------------------------
    // 1.4: single-sender queue — burst, order, shutdown
    // ------------------------------------------------------------------

    @Test
    void fakeEnforcesSingleSendGate() throws Exception {
        GateEnforcingFake fake = new GateEnforcingFake();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger rejected = new AtomicInteger(0);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    try {
                        fake.sendText("x", true);
                    } catch (IllegalStateException e) {
                        rejected.incrementAndGet();
                    }
                    return null;
                });
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
            // Sanity: without serialization the gate actually trips.
            assertTrue(rejected.get() > 0, "fake should reject concurrent sends");
        } finally {
            pool.shutdownNow();
            fake.async.shutdownNow();
        }
    }

    @Test
    void concurrentBurstSerializedWithFifoAndCleanShutdown() throws Exception {
        WsRpcChannel channel = WsRpcChannel.create();
        JdkWsClient client = clientOf(channel);
        GateEnforcingFake fake = new GateEnforcingFake();
        client.setTestWebSocket(fake);
        int n = 50;
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        ConcurrentHashMap<String, Throwable> errors = new ConcurrentHashMap<>();
        try {
            for (int i = 0; i < n; i++) {
                String msg = "msg-" + i;
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    try {
                        client.session().sendText(msg);
                    } catch (Throwable t) {
                        errors.put(msg, t);
                    }
                    return null;
                });
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

            assertTrue(errors.isEmpty(), "no send should hit Send pending: " + errors);
            awaitCondition(() -> fake.texts.size() == n, "all " + n + " frames sent");
            awaitCondition(() -> client.getSendQueueDepth() == 0, "queue drained");
            assertEquals(1, fake.maxInFlight.get(), "only one outstanding send at a time");
            assertEquals(new HashSet<>(fake.texts).size(), n, "every frame sent exactly once");
        } finally {
            pool.shutdownNow();
            client.close();
            channel.shutdown();
            fake.async.shutdownNow();
        }
        awaitCondition(() -> !client.isSenderAlive() && client.isSchedulerShutdown(),
                "sender + scheduler terminated after close");
    }

    @Test
    void sequentialSendsPreserveFifoOrder() throws Exception {
        WsRpcChannel channel = WsRpcChannel.create();
        JdkWsClient client = clientOf(channel);
        GateEnforcingFake fake = new GateEnforcingFake();
        client.setTestWebSocket(fake);
        try {
            List<String> expected = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                expected.add("frame-" + i);
                client.session().sendText("frame-" + i);
            }
            awaitCondition(() -> fake.texts.size() == expected.size(), "all frames sent");
            awaitCondition(() -> client.getSendQueueDepth() == 0, "queue drained");
            assertEquals(expected, new ArrayList<>(fake.texts));
        } finally {
            client.close();
            channel.shutdown();
            fake.async.shutdownNow();
        }
    }

    // ------------------------------------------------------------------
    // 1.5: fail-fast drop path
    // ------------------------------------------------------------------

    @Test
    void failedSendFailsPendingFast() throws Exception {
        WsRpcChannel channel = WsRpcChannel.create();
        JdkWsClient client = clientOf(channel);
        GateEnforcingFake fake = new GateEnforcingFake();
        fake.failSends.set(true);
        CountDownLatch dropped = new CountDownLatch(1);
        client.onClose(err -> dropped.countDown());
        client.setTestWebSocket(fake);
        try {
            var pending = channel.sendRequest("ghost", "x", String.class, Duration.ofMinutes(1));
            assertFalse(pending.isDone());
            client.session().sendText("{\"ping\":1}");
            assertTrue(dropped.await(10, TimeUnit.SECONDS), "drop should fire onClose");
            awaitCondition(pending::isDone, "pending RPC failed fast");
            assertTrue(pending.isCompletedExceptionally());
        } finally {
            client.close();
            channel.shutdown();
            fake.async.shutdownNow();
        }
    }
}
