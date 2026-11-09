package gateway.client;

import java.net.URI;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import gateway.rpc.RpcChannel;
import gateway.wire.StreamReply;
import gateway.wire.WsMessage;
import gateway.wire.WsProtocol;

/**
 * Probe for backend-gateway head-of-line blocking.
 *
 * <p>After the drain-queue interleave, a queued control frame cuts in
 * within 3 binary frames (never waiting for the full stream) while
 * per-stream order is preserved. Control latency must stay capped
 * as chunk count grows, RPC handlers still run in parallel, and the
 * image still drains when no control traffic exists.
 */
class HeadOfLineProbeTest {

    static class ControllableFake implements WebSocket {
        final List<String> texts = Collections.synchronizedList(new ArrayList<>());
        final List<String> order = Collections.synchronizedList(new ArrayList<>());
        final List<Long> completedAtNanos = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger binaries = new AtomicInteger(0);
        final AtomicInteger inFlight = new AtomicInteger(0);
        final AtomicInteger maxInFlight = new AtomicInteger(0);
        final AtomicBoolean sendPending = new AtomicBoolean(false);
        final int delayMs;
        final ExecutorService async;
        volatile boolean outputClosed = false;

        ControllableFake(int delayMs) {
            this.delayMs = delayMs;
            this.async = Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "probe-fake-async");
                t.setDaemon(true);
                return t;
            });
        }

        private CompletableFuture<WebSocket> guarded(Runnable record) {
            if (!sendPending.compareAndSet(false, true)) {
                throw new IllegalStateException("Send pending");
            }
            int cur = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(cur, Math::max);
            return CompletableFuture.supplyAsync(() -> {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
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
                completedAtNanos.add(System.nanoTime());
            });
        }

        @Override
        public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) {
            return guarded(() -> {
                binaries.incrementAndGet();
                order.add("binary");
                completedAtNanos.add(System.nanoTime());
            });
        }

        @Override
        public CompletableFuture<WebSocket> sendPing(ByteBuffer message) {
            return guarded(() -> {
                order.add("ping");
                completedAtNanos.add(System.nanoTime());
            });
        }

        @Override
        public CompletableFuture<WebSocket> sendPong(ByteBuffer message) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
            outputClosed = true;
            return guarded(() -> order.add("close"));
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

    static class StubEvents implements WebSocketConnection.Events {
        @Override
        public void onRemoteClose(WebSocketConnection transport, int statusCode, String reason) {
        }

        @Override
        public void onTransportError(WebSocketConnection transport, Throwable error) {
        }
    }

    static class ProbeDialer implements WsDialer {
        final List<ControllableFake> fakes = new CopyOnWriteArrayList<>();
        final List<WebSocket.Listener> listeners = new CopyOnWriteArrayList<>();
        final int delayMs;

        ProbeDialer(int delayMs) {
            this.delayMs = delayMs;
        }

        @Override
        public CompletableFuture<WebSocket> dial(WebSocket.Listener listener) {
            ControllableFake fake = new ControllableFake(delayMs);
            fakes.add(fake);
            listeners.add(listener);
            return CompletableFuture.completedFuture(fake);
        }

        void shutdown() {
            fakes.forEach(fake -> fake.async.shutdownNow());
        }
    }

    private static void awaitCondition(Supplier<Boolean> cond, String what) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!cond.get()) {
            if (System.nanoTime() > deadline) {
                fail("Timed out waiting for: " + what);
            }
            Thread.sleep(2);
        }
    }

    private static ScheduledExecutorService daemonScheduler(String name) {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        });
    }

    private static int indexOfText(ControllableFake fake, String needle) {
        List<String> snapshot = new ArrayList<>(fake.order);
        for (int i = 0; i < snapshot.size(); i++) {
            String frame = snapshot.get(i);
            if (frame.startsWith("text:") && frame.contains(needle)) {
                return i;
            }
        }
        return -1;
    }

    private static long measureControlLatency(int chunkCount, int delayMs, int[] outIndex)
            throws Exception {
        ScheduledExecutorService scheduler = daemonScheduler("probe-unit-scheduler");
        RpcChannel channel = RpcChannel.create();
        ControllableFake fake = new ControllableFake(delayMs);
        WebSocketConnection conn = new WebSocketConnection(
                scheduler, Duration.ofHours(1), Duration.ofHours(2), Duration.ofSeconds(10), channel,
                new StubEvents());
        try {
            conn.attach(fake);
            conn.beginRead();
            ByteBuffer chunk = ByteBuffer.allocate(64 * 1024);
            long t0 = System.nanoTime();
            for (int i = 0; i < chunkCount; i++) {
                conn.sendBinary(chunk);
            }
            conn.sendText("control");
            awaitCondition(() -> fake.order.size() == chunkCount + 1, "all frames sent");
            awaitCondition(() -> conn.queueDepth() == 0, "queue drained");
            assertEquals(1, fake.maxInFlight.get(), "only one outstanding send at a time");
            int idx = indexOfText(fake, "control");
            outIndex[0] = idx;
            assertTrue(idx >= 0 && idx <= 6,
                    "control cuts in within few frames, idx=" + idx + " chunks=" + chunkCount);
            assertTrue(idx < chunkCount, "control does not wait for full stream");
            long controlCompleted = fake.completedAtNanos.get(idx);
            return TimeUnit.NANOSECONDS.toMillis(controlCompleted - t0);
        } finally {
            try {
                conn.terminate();
            } catch (Exception ignored) {
            }
            scheduler.shutdownNow();
            fake.async.shutdownNow();
            channel.shutdown();
        }
    }

    @Test
    void unitDrainInterleavesControlWithinBinaryStream() throws Exception {
        int[] idx = new int[1];
        long latencyMs = measureControlLatency(20, 5, idx);
        assertTrue(latencyMs < 100, "control capped under 100ms, got " + latencyMs + "ms idx=" + idx[0]);
        assertTrue(idx[0] <= 6, "control cuts in within few frames, idx=" + idx[0]);
    }

    @Test
    void latencyCappedAsChunksGrow() throws Exception {
        int[] smallIdx = new int[1];
        int[] largeIdx = new int[1];
        long small = measureControlLatency(4, 5, smallIdx);
        long large = measureControlLatency(20, 5, largeIdx);
        assertTrue(small < 100 && large < 100,
                "both under 100ms cap: small=" + small + "ms large=" + large + "ms");
        assertTrue(large - small < 60,
                "latency must not scale linearly with chunks: small=" + small + "ms large="
                        + large + "ms");
    }

    @Test
    void imageDrainsFullyWhenNoControlExists() throws Exception {
        ScheduledExecutorService scheduler = daemonScheduler("probe-drain-scheduler");
        RpcChannel channel = RpcChannel.create();
        ControllableFake fake = new ControllableFake(2);
        WebSocketConnection conn = new WebSocketConnection(
                scheduler, Duration.ofHours(1), Duration.ofHours(2), Duration.ofSeconds(10), channel,
                new StubEvents());
        try {
            conn.attach(fake);
            conn.beginRead();
            ByteBuffer chunk = ByteBuffer.allocate(64 * 1024);
            for (int i = 0; i < 10; i++) {
                conn.sendBinary(chunk);
            }
            awaitCondition(() -> fake.order.size() == 10, "all binaries sent, no freeze");
            awaitCondition(() -> conn.queueDepth() == 0, "queue drained");
            assertEquals(10, fake.binaries.get());
            assertEquals(1, fake.maxInFlight.get());
        } finally {
            try {
                conn.terminate();
            } catch (Exception ignored) {
            }
            scheduler.shutdownNow();
            fake.async.shutdownNow();
            channel.shutdown();
        }
    }

    @Test
    void binaryStillProgressesUnderControlBurst() throws Exception {
        ScheduledExecutorService scheduler = daemonScheduler("probe-burst-scheduler");
        RpcChannel channel = RpcChannel.create();
        ControllableFake fake = new ControllableFake(2);
        WebSocketConnection conn = new WebSocketConnection(
                scheduler, Duration.ofHours(1), Duration.ofHours(2), Duration.ofSeconds(10), channel,
                new StubEvents());
        try {
            conn.attach(fake);
            conn.beginRead();
            for (int i = 0; i < 6; i++) {
                conn.sendText("ctrl-" + i);
            }
            ByteBuffer chunk = ByteBuffer.allocate(64 * 1024);
            for (int i = 0; i < 6; i++) {
                conn.sendBinary(chunk);
            }
            awaitCondition(() -> fake.order.size() == 12, "all frames sent");
            List<String> order = new ArrayList<>(fake.order);
            int firstBinary = order.indexOf("binary");
            assertTrue(firstBinary >= 0 && firstBinary <= 3,
                    "binary still progresses after at most 3 controls, firstBinary=" + firstBinary
                            + " order=" + order);
        } finally {
            try {
                conn.terminate();
            } catch (Exception ignored) {
            }
            scheduler.shutdownNow();
            fake.async.shutdownNow();
            channel.shutdown();
        }
    }

    @Test
    void e2eGetImageReplyCutsInForControlWhileHandlersStayParallel() throws Exception {
        int delayMs = 5;
        int imageBytes = 20 * 64 * 1024;
        RpcChannel channel = RpcChannel.withExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "probe-handler");
            t.setDaemon(true);
            return t;
        }));
        ProbeDialer dialer = new ProbeDialer(delayMs);
        WsClient client = WsClient.builder(URI.create("ws://localhost:1/gateway"), channel)
                .dialer(dialer)
                .pingInterval(Duration.ofHours(1))
                .pongDeadline(Duration.ofHours(2))
                .build();
        AtomicLong getStateHandlerNanos = new AtomicLong();
        AtomicLong getImageHandlerNanos = new AtomicLong();
        CountDownLatch getStateHandled = new CountDownLatch(1);
        CountDownLatch getImageHandled = new CountDownLatch(1);
        try {
            channel.registerHandler("get-image", Void.class, _ctx -> {
                getImageHandlerNanos.set(System.nanoTime());
                getImageHandled.countDown();
                return new StreamReply(new byte[imageBytes],
                        Map.of("fileName", "probe.png", "size", imageBytes));
            });
            channel.registerHandler("get-state", Void.class, _ctx -> {
                getStateHandlerNanos.set(System.nanoTime());
                countDownQuietly(getStateHandled);
                return "ok";
            });
            client.connect();
            awaitCondition(client::isOpen, "client open");
            ControllableFake fake = dialer.fakes.get(0);

            String getImageReq = channel.getObjectMapper().writeValueAsString(
                    WsMessage.create(WsProtocol.REQUEST_TYPE).setType("get-image"));
            long tImageReq = System.nanoTime();
            channel.onTextMessage(channel.current(), getImageReq);
            awaitCondition(() -> fake.binaries.get() >= 1, "reply stream started");
            String getStateReq = channel.getObjectMapper().writeValueAsString(
                    WsMessage.create(WsProtocol.REQUEST_TYPE).setType("get-state"));
            channel.onTextMessage(channel.current(), getStateReq);
            assertTrue(getStateHandled.await(5, TimeUnit.SECONDS), "get-state handler ran during image transfer");
            assertTrue(getImageHandled.await(5, TimeUnit.SECONDS), "get-image handler ran");
            long handlerDeltaMs = TimeUnit.NANOSECONDS.toMillis(getStateHandlerNanos.get() - tImageReq);
            channel.current().sendText("usage-probe");
            awaitCondition(() -> fake.binaries.get() == 20, "all image chunks sent");
            awaitCondition(() -> indexOfText(fake, "\"type\":\"get-state\"") >= 0
                    && indexOfText(fake, "usage-probe") >= 0, "control frames sent");

            List<String> order = new ArrayList<>(fake.order);
            long binaries = order.stream().filter("binary"::equals).count();
            assertEquals(20, binaries);
            int stateResponse = indexOfText(fake, "\"type\":\"get-state\"");
            int usageProbe = indexOfText(fake, "usage-probe");
            int firstBinary = order.indexOf("binary");
            assertTrue(firstBinary >= 0);
            assertTrue(stateResponse >= 0 && stateResponse <= firstBinary + 6,
                    "get-state response cuts in within few frames, order=" + order);
            assertTrue(usageProbe >= 0 && usageProbe <= firstBinary + 6,
                    "usage-style frame cuts in within few frames, order=" + order);
            assertTrue(handlerDeltaMs < 5000, "handler ran in parallel, delta=" + handlerDeltaMs + "ms");
            assertEquals(1, fake.maxInFlight.get());
            awaitCondition(() -> client.getTransportQueueDepth() == 0, "queue drained");
        } finally {
            client.close();
            channel.shutdown();
            dialer.shutdown();
        }
    }

    private static void countDownQuietly(CountDownLatch latch) {
        try {
            latch.countDown();
        } catch (Exception ignored) {
        }
    }
}
