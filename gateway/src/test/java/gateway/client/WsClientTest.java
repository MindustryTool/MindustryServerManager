package gateway.client;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import gateway.rpc.WsProtocol;
import gateway.rpc.WsRpcChannel;

/**
 * Covers the gateway client through the injected dialer seam: builder
 * validation, per-attempt headers, stream order, the single-sender queue,
 * fail-fast drop, the kick signal, and the connection state machine.
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
        final AtomicInteger requests = new AtomicInteger(0);
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
            requests.incrementAndGet();
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

    static class TestDialer implements WsDialer {
        final List<GateEnforcingFake> fakes = new CopyOnWriteArrayList<>();
        final List<WebSocket.Listener> listeners = new CopyOnWriteArrayList<>();

        @Override
        public CompletableFuture<WebSocket> dial(WebSocket.Listener listener) {
            GateEnforcingFake fake = new GateEnforcingFake();
            fakes.add(fake);
            listeners.add(listener);
            return CompletableFuture.completedFuture(fake);
        }

        void shutdown() {
            fakes.forEach(fake -> fake.async.shutdownNow());
        }
    }

    private static JdkWsClient connectedClient(WsRpcChannel channel, TestDialer dialer) throws Exception {
        JdkWsClient client = JdkWsClient
                .builder(URI.create("ws://localhost:1/gateway"), channel)
                .dialer(dialer)
                .build();
        client.connect();
        awaitCondition(() -> client.getState() == JdkWsClient.State.OPEN, "client open");
        return client;
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
    // Builder validation
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
    // Headers supplier invoked per attempt, blanks filtered
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
            client.connect();
            awaitCondition(() -> calls.get() == 2, "supplier resolved on dial attempt");
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
        TestDialer dialer = new TestDialer();
        JdkWsClient client = connectedClient(channel, dialer);
        GateEnforcingFake fake = dialer.fakes.get(0);
        try {
            byte[] data = new byte[150 * 1024];
            Arrays.fill(data, (byte) 5);
            channel.sendStream("big", "m", data, Void.class, Duration.ofSeconds(10));

            awaitCondition(() -> fake.texts.size() == 2 && fake.binaries.get() == 3, "start + 3 chunks + done");
            awaitCondition(() -> client.getTransportQueueDepth() == 0, "queue drained");

            List<String> order = new ArrayList<>(fake.order);
            assertEquals(5, order.size());
            assertTrue(order.get(0).contains("\"kind\":\"" + WsProtocol.STREAM_START_TYPE + "\""),
                    "start first, got: " + order.get(0));
            assertEquals("binary", order.get(1));
            assertEquals("binary", order.get(2));
            assertEquals("binary", order.get(3));
            assertTrue(order.get(4).contains("\"kind\":\"" + WsProtocol.STREAM_DONE_TYPE + "\""),
                    "done last, got: " + order.get(4));
        } finally {
            client.close();
            channel.shutdown();
            dialer.shutdown();
        }
    }

    @Test
    void noReadGrantedBeforeAdoptionAndOnOpenDoesNotSelfRequest() throws Exception {
        WsRpcChannel channel = WsRpcChannel.create();
        TestDialer dialer = new TestDialer();
        JdkWsClient client = connectedClient(channel, dialer);
        GateEnforcingFake fake = dialer.fakes.get(0);
        WebSocket.Listener listener = dialer.listeners.get(0);
        try {
            awaitCondition(() -> fake.requests.get() == 1, "exactly one read granted once adopted");
            listener.onOpen(fake);
            assertEquals(1, fake.requests.get(), "listener onOpen must not grant another read");
        } finally {
            client.close();
            channel.shutdown();
            dialer.shutdown();
        }
    }

    // ------------------------------------------------------------------
    // Single-sender queue — burst, order, shutdown
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
        TestDialer dialer = new TestDialer();
        JdkWsClient client = connectedClient(channel, dialer);
        GateEnforcingFake fake = dialer.fakes.get(0);
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
                        channel.getSession().sendText(msg);
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
            awaitCondition(() -> client.getTransportQueueDepth() == 0, "queue drained");
            assertEquals(1, fake.maxInFlight.get(), "only one outstanding send at a time");
            assertEquals(new HashSet<>(fake.texts).size(), n, "every frame sent exactly once");
        } finally {
            pool.shutdownNow();
            client.close();
            channel.shutdown();
            dialer.shutdown();
        }
        awaitCondition(() -> client.isSchedulerShutdown(), "scheduler terminated after close");
    }

    @Test
    void sequentialSendsPreserveFifoOrder() throws Exception {
        WsRpcChannel channel = WsRpcChannel.create();
        TestDialer dialer = new TestDialer();
        JdkWsClient client = connectedClient(channel, dialer);
        GateEnforcingFake fake = dialer.fakes.get(0);
        try {
            List<String> expected = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                expected.add("frame-" + i);
                channel.getSession().sendText("frame-" + i);
            }
            awaitCondition(() -> fake.texts.size() == expected.size(), "all frames sent");
            awaitCondition(() -> client.getTransportQueueDepth() == 0, "queue drained");
            assertEquals(expected, new ArrayList<>(fake.texts));
        } finally {
            client.close();
            channel.shutdown();
            dialer.shutdown();
        }
    }

    // ------------------------------------------------------------------
    // Fail-fast drop path
    // ------------------------------------------------------------------

    @Test
    void failedSendFailsPendingFast() throws Exception {
        WsRpcChannel channel = WsRpcChannel.create();
        TestDialer dialer = new TestDialer();
        JdkWsClient client = connectedClient(channel, dialer);
        GateEnforcingFake fake = dialer.fakes.get(0);
        fake.failSends.set(true);
        CountDownLatch dropped = new CountDownLatch(1);
        client.onClose(err -> dropped.countDown());
        try {
            var pending = channel.sendRequest("ghost", "x", String.class, Duration.ofMinutes(1));
            assertFalse(pending.isDone());
            channel.getSession().sendText("{\"ping\":1}");
            assertTrue(dropped.await(10, TimeUnit.SECONDS), "drop should fire onClose");
            awaitCondition(pending::isDone, "pending RPC failed fast");
            assertTrue(pending.isCompletedExceptionally());
        } finally {
            client.close();
            channel.shutdown();
            dialer.shutdown();
        }
    }

    // ------------------------------------------------------------------
    // State machine: kick, stale signals, terminal close
    // ------------------------------------------------------------------

    @Test
    void kick4234StopsReconnectAndManualConnectRevives() throws Exception {
        WsRpcChannel channel = WsRpcChannel.create();
        TestDialer dialer = new TestDialer();
        JdkWsClient client = connectedClient(channel, dialer);
        GateEnforcingFake fake = dialer.fakes.get(0);
        WebSocket.Listener listener = dialer.listeners.get(0);
        CountDownLatch closed = new CountDownLatch(1);
        client.onClose(err -> closed.countDown());
        try {
            listener.onClose(fake, WsProtocol.REPLACED_CLOSE_CODE, "Replaced by new connection");

            assertTrue(closed.await(10, TimeUnit.SECONDS), "kick should fire onClose");
            assertEquals(JdkWsClient.State.KICKED, client.getState());
            assertFalse(client.isOpen());
            assertEquals(0, client.getReconnectAttempt(), "kick must not schedule reconnect");

            client.connect();
            awaitCondition(() -> client.getState() == JdkWsClient.State.OPEN, "manual connect revives");
            assertNotNull(channel.getSession());
        } finally {
            client.close();
            channel.shutdown();
            dialer.shutdown();
        }
    }

    @Test
    void duplicateDropSignalsIgnored() throws Exception {
        WsRpcChannel channel = WsRpcChannel.create();
        TestDialer dialer = new TestDialer();
        JdkWsClient client = connectedClient(channel, dialer);
        GateEnforcingFake fake = dialer.fakes.get(0);
        WebSocket.Listener listener = dialer.listeners.get(0);
        AtomicInteger closes = new AtomicInteger(0);
        client.onClose(err -> closes.incrementAndGet());
        try {
            listener.onClose(fake, 1000, "bye");
            assertEquals(1, closes.get());
            assertEquals(JdkWsClient.State.RECONNECT_WAIT, client.getState());

            listener.onClose(fake, 1000, "bye again");
            assertEquals(1, closes.get(), "duplicate drop must not refire onClose");
            assertEquals(JdkWsClient.State.RECONNECT_WAIT, client.getState());
        } finally {
            client.close();
            channel.shutdown();
            dialer.shutdown();
        }
    }

    @Test
    void lateKickAfterKickIgnored() throws Exception {
        WsRpcChannel channel = WsRpcChannel.create();
        TestDialer dialer = new TestDialer();
        JdkWsClient client = connectedClient(channel, dialer);
        GateEnforcingFake fake = dialer.fakes.get(0);
        WebSocket.Listener listener = dialer.listeners.get(0);
        AtomicInteger closes = new AtomicInteger(0);
        client.onClose(err -> closes.incrementAndGet());
        try {
            listener.onClose(fake, WsProtocol.REPLACED_CLOSE_CODE, "kicked");
            assertEquals(1, closes.get());
            assertEquals(JdkWsClient.State.KICKED, client.getState());

            listener.onClose(fake, WsProtocol.REPLACED_CLOSE_CODE, "kicked again");
            assertEquals(1, closes.get(), "late kick must not refire onClose");
            assertEquals(JdkWsClient.State.KICKED, client.getState());
            assertEquals(0, client.getReconnectAttempt());
        } finally {
            client.close();
            channel.shutdown();
            dialer.shutdown();
        }
    }

    @Test
    void closeIsTerminal() throws Exception {
        WsRpcChannel channel = WsRpcChannel.create();
        TestDialer dialer = new TestDialer();
        JdkWsClient client = connectedClient(channel, dialer);
        try {
            client.close();

            assertEquals(JdkWsClient.State.CLOSED, client.getState());
            assertFalse(client.isOpen());

            client.connect();
            Thread.sleep(200);
            assertEquals(JdkWsClient.State.CLOSED, client.getState(), "closed client never redials");
            assertEquals(1, dialer.fakes.size(), "no second dial after close");
        } finally {
            channel.shutdown();
            dialer.shutdown();
        }
    }

    @Test
    void dropSchedulesReconnectAndRedials() throws Exception {
        WsRpcChannel channel = WsRpcChannel.create();
        TestDialer dialer = new TestDialer();
        JdkWsClient client = connectedClient(channel, dialer);
        GateEnforcingFake fake = dialer.fakes.get(0);
        WebSocket.Listener listener = dialer.listeners.get(0);
        try {
            listener.onClose(fake, 1000, "bye");
            assertEquals(JdkWsClient.State.RECONNECT_WAIT, client.getState());

            awaitCondition(() -> client.getState() == JdkWsClient.State.OPEN, "reconnect redials");
            assertEquals(2, dialer.fakes.size(), "second dial after drop");
            assertNotNull(channel.getSession());
        } finally {
            client.close();
            channel.shutdown();
            dialer.shutdown();
        }
    }

    // ------------------------------------------------------------------
    // Channel-level session wait (client exposes none)
    // ------------------------------------------------------------------

    @Test
    void channelAwaitSessionWaitsForLateOpen() throws Exception {
        WsRpcChannel channel = WsRpcChannel.create();
        TestDialer dialer = new TestDialer();
        JdkWsClient client = JdkWsClient
                .builder(URI.create("ws://localhost:1/gateway"), channel)
                .dialer(dialer)
                .build();
        try {
            var waiting = channel.awaitSession(Duration.ofSeconds(5));
            assertFalse(waiting.isDone(), "no session yet, must wait not fail");

            client.connect();
            assertNotNull(waiting.get(5, TimeUnit.SECONDS));
        } finally {
            client.close();
            channel.shutdown();
            dialer.shutdown();
        }
    }

    @Test
    void channelAwaitSessionTimesOutWithoutConnection() {
        WsRpcChannel channel = WsRpcChannel.create();
        try {
            var waiting = channel.awaitSession(Duration.ofMillis(100));
            Exception err = assertThrows(Exception.class, () -> waiting.get(5, TimeUnit.SECONDS));
            assertTrue(err instanceof TimeoutException
                    || err.getCause() instanceof TimeoutException,
                    "expected TimeoutException, got: " + err);
        } finally {
            channel.shutdown();
        }
    }
}
