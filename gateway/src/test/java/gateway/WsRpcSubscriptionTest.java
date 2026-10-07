package gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import gateway.rpc.WsProtocol;
import gateway.rpc.WsRpcChannel;
import gateway.rpc.PushHandle;
import gateway.session.WsSession;

class WsRpcSubscriptionTest {

    record UsageParams(String serverId) {
    }

    record UsageEvent(int cpu, String mem, String timestamp) {
    }

    static class Loopback implements WsSession {
        WsRpcChannel peer;
        final List<String> sent = new CopyOnWriteArrayList<>();
        volatile boolean open = true;
        volatile boolean dropListen = false;

        @Override
        public void sendText(String text) {
            if (dropListen && text.contains("\"kind\":\"" + WsProtocol.LISTEN_TYPE + "\"")) {
                return;
            }
            sent.add(text);
            WsRpcChannel p = peer;
            if (p != null) {
                p.onTextMessage(text);
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

    private static WsRpcChannel[] pair(Loopback sa, Loopback sb) {
        WsRpcChannel a = WsRpcChannel.create();
        WsRpcChannel b = WsRpcChannel.create();
        sa.peer = b;
        sb.peer = a;
        a.onOpen(sa);
        b.onOpen(sb);
        return new WsRpcChannel[] { a, b };
    }

    private static JsonNode firstSentOfKind(List<String> sent, String kind) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        for (String text : sent) {
            if (text.contains("\"kind\":\"" + kind + "\"")) {
                return mapper.readTree(text);
            }
        }
        return null;
    }

    @Test
    void listenRoundTripWithEvent() throws Exception {
        Loopback sa = new Loopback();
        Loopback sb = new Loopback();
        WsRpcChannel[] pair = pair(sa, sb);
        WsRpcChannel client = pair[0];
        WsRpcChannel server = pair[1];

        CountDownLatch listenLatch = new CountDownLatch(1);
        CountDownLatch eventLatch = new CountDownLatch(1);
        AtomicReference<PushHandle> handleRef = new AtomicReference<>();
        final UsageEvent[] receivedEvent = new UsageEvent[1];

        server.registerEventListener("usage", UsageParams.class, req -> {
            assertEquals("srv-123", req.params().serverId());
            handleRef.set(req.handle());
            listenLatch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        CompletableFuture<Void> ack = client.listen("usage", new UsageParams("srv-123"), event -> {
            try {
                receivedEvent[0] = new ObjectMapper().treeToValue(event, UsageEvent.class);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            eventLatch.countDown();
        }, Duration.ofSeconds(5));

        assertTrue(listenLatch.await(5, TimeUnit.SECONDS), "server onListen not called");
        ack.get(5, TimeUnit.SECONDS);

        assertNotNull(handleRef.get());
        handleRef.get().push(new UsageEvent(45, "2GB", "2026-10-06T12:00:00Z"));

        assertTrue(eventLatch.await(5, TimeUnit.SECONDS), "event not received");
        assertEquals(45, receivedEvent[0].cpu());
        assertEquals("2GB", receivedEvent[0].mem());

        client.shutdown();
        server.shutdown();
    }

    @Test
    void listeningAckArrivesBeforeEvents() throws Exception {
        Loopback sa = new Loopback();
        Loopback sb = new Loopback();
        WsRpcChannel[] pair = pair(sa, sb);
        WsRpcChannel client = pair[0];
        WsRpcChannel server = pair[1];

        server.registerEventListener("usage", UsageParams.class,
                req -> CompletableFuture.completedFuture(null));

        CompletableFuture<Void> ack = client.listen("usage", new UsageParams("srv-123"), event -> {},
                Duration.ofSeconds(5));
        ack.get(5, TimeUnit.SECONDS);

        JsonNode listening = firstSentOfKind(sb.sent, WsProtocol.LISTENING_TYPE);
        assertNotNull(listening, "a dedicated listening ack must be sent, sent: " + sb.sent);
        assertEquals("usage", listening.get("event").asText());
        assertTrue(listening.get("type").isNull(), "event frames must not carry a type field");

        client.shutdown();
        server.shutdown();
    }

    @Test
    void multipleListenersSameEventDifferentParams() throws Exception {
        Loopback sa = new Loopback();
        Loopback sb = new Loopback();
        WsRpcChannel[] pair = pair(sa, sb);
        WsRpcChannel client = pair[0];
        WsRpcChannel server = pair[1];

        ConcurrentHashMap<String, PushHandle> handles = new ConcurrentHashMap<>();
        CountDownLatch listenLatch = new CountDownLatch(2);
        CountDownLatch eventLatch1 = new CountDownLatch(1);
        CountDownLatch eventLatch2 = new CountDownLatch(1);
        final UsageEvent[] receivedEvent1 = new UsageEvent[1];
        final UsageEvent[] receivedEvent2 = new UsageEvent[1];

        server.registerEventListener("usage", UsageParams.class, req -> {
            handles.put(req.params().serverId(), req.handle());
            listenLatch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        CompletableFuture<Void> ack1 = client.listen("usage", new UsageParams("srv-1"), event -> {
            try {
                receivedEvent1[0] = new ObjectMapper().treeToValue(event, UsageEvent.class);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            eventLatch1.countDown();
        }, Duration.ofSeconds(5));

        CompletableFuture<Void> ack2 = client.listen("usage", new UsageParams("srv-2"), event -> {
            try {
                receivedEvent2[0] = new ObjectMapper().treeToValue(event, UsageEvent.class);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            eventLatch2.countDown();
        }, Duration.ofSeconds(5));

        assertTrue(listenLatch.await(5, TimeUnit.SECONDS));
        ack1.get(5, TimeUnit.SECONDS);
        ack2.get(5, TimeUnit.SECONDS);

        handles.get("srv-1").push(new UsageEvent(45, "2GB", "2026-10-06T12:00:00Z"));
        handles.get("srv-2").push(new UsageEvent(80, "4GB", "2026-10-06T12:00:01Z"));

        assertTrue(eventLatch1.await(5, TimeUnit.SECONDS));
        assertTrue(eventLatch2.await(5, TimeUnit.SECONDS));
        assertEquals(45, receivedEvent1[0].cpu());
        assertEquals(80, receivedEvent2[0].cpu());

        client.shutdown();
        server.shutdown();
    }

    @Test
    void clientUnlistenStopsEvents() throws Exception {
        Loopback sa = new Loopback();
        Loopback sb = new Loopback();
        WsRpcChannel[] pair = pair(sa, sb);
        WsRpcChannel client = pair[0];
        WsRpcChannel server = pair[1];

        AtomicReference<PushHandle> handleRef = new AtomicReference<>();
        CountDownLatch listenLatch = new CountDownLatch(1);
        AtomicBoolean receivedAfterUnlisten = new AtomicBoolean(false);

        server.registerEventListener("usage", UsageParams.class, req -> {
            handleRef.set(req.handle());
            listenLatch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        UUID listenId = UUID.randomUUID();
        CompletableFuture<Void> ack = client.listen(listenId, "usage", new UsageParams("srv-123"), event -> {
            receivedAfterUnlisten.set(true);
        }, Duration.ofSeconds(5));

        assertTrue(listenLatch.await(5, TimeUnit.SECONDS));
        ack.get(5, TimeUnit.SECONDS);

        client.unlisten(listenId, "test reason");

        handleRef.get().push(new UsageEvent(45, "2GB", "2026-10-06T12:00:00Z"));
        Thread.sleep(100);

        assertFalse(receivedAfterUnlisten.get());

        client.shutdown();
        server.shutdown();
    }

    @Test
    void unlistenConfirmedByListenEnded() throws Exception {
        Loopback sa = new Loopback();
        Loopback sb = new Loopback();
        WsRpcChannel[] pair = pair(sa, sb);
        WsRpcChannel client = pair[0];
        WsRpcChannel server = pair[1];

        server.registerEventListener("usage", UsageParams.class,
                req -> CompletableFuture.completedFuture(null));

        UUID listenId = UUID.randomUUID();
        CompletableFuture<Void> ack = client.listen(listenId, "usage", new UsageParams("srv-123"), event -> {},
                Duration.ofSeconds(5));
        ack.get(5, TimeUnit.SECONDS);

        client.unlisten(listenId);

        JsonNode ended = firstSentOfKind(sb.sent, WsProtocol.LISTEN_ENDED_TYPE);
        long deadline = System.currentTimeMillis() + 5000;
        while (ended == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
            ended = firstSentOfKind(sb.sent, WsProtocol.LISTEN_ENDED_TYPE);
        }
        assertNotNull(ended, "unlisten must be confirmed with listen-ended, sent: " + sb.sent);
        assertEquals(listenId.toString(), ended.get("responseOf").asText());
        assertEquals("usage", ended.get("event").asText());

        client.shutdown();
        server.shutdown();
    }

    @Test
    void serverFailClosesEventStream() throws Exception {
        Loopback sa = new Loopback();
        Loopback sb = new Loopback();
        WsRpcChannel[] pair = pair(sa, sb);
        WsRpcChannel client = pair[0];
        WsRpcChannel server = pair[1];

        AtomicReference<PushHandle> handleRef = new AtomicReference<>();
        CountDownLatch listenLatch = new CountDownLatch(1);
        CountDownLatch clientCloseLatch = new CountDownLatch(1);

        server.registerEventListener("usage", UsageParams.class, req -> {
            handleRef.set(req.handle());
            listenLatch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        UUID listenId = UUID.randomUUID();
        client.onListenClose(listenId, clientCloseLatch::countDown);

        CompletableFuture<Void> ack = client.listen(listenId, "usage", new UsageParams("srv-123"), event -> {},
                Duration.ofSeconds(5));

        assertTrue(listenLatch.await(5, TimeUnit.SECONDS));
        ack.get(5, TimeUnit.SECONDS);

        handleRef.get().fail("server not found: srv-123");

        assertTrue(clientCloseLatch.await(5, TimeUnit.SECONDS), "client onClose callback should trigger on server fail");
        assertTrue(handleRef.get().isClosed());

        JsonNode error = firstSentOfKind(sb.sent, WsProtocol.LISTEN_ERROR_TYPE);
        assertNotNull(error, "failure must be a listen-error kind, sent: " + sb.sent);
        assertEquals(listenId.toString(), error.get("responseOf").asText());

        client.shutdown();
        server.shutdown();
    }

    @Test
    void serverCompleteEndsStreamAndNotifiesClient() throws Exception {
        Loopback sa = new Loopback();
        Loopback sb = new Loopback();
        WsRpcChannel[] pair = pair(sa, sb);
        WsRpcChannel client = pair[0];
        WsRpcChannel server = pair[1];

        AtomicReference<PushHandle> handleRef = new AtomicReference<>();
        CountDownLatch listenLatch = new CountDownLatch(1);
        CountDownLatch serverCloseLatch = new CountDownLatch(1);
        CountDownLatch clientCloseLatch = new CountDownLatch(1);

        server.registerEventListener("usage", UsageParams.class, req -> {
            req.handle().onClose(serverCloseLatch::countDown);
            handleRef.set(req.handle());
            listenLatch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        UUID listenId = UUID.randomUUID();
        client.onListenClose(listenId, clientCloseLatch::countDown);
        CompletableFuture<Void> ack = client.listen(listenId, "usage", new UsageParams("srv-123"), event -> {},
                Duration.ofSeconds(5));

        assertTrue(listenLatch.await(5, TimeUnit.SECONDS));
        ack.get(5, TimeUnit.SECONDS);

        handleRef.get().complete();

        assertTrue(handleRef.get().isClosed());
        assertTrue(serverCloseLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientCloseLatch.await(5, TimeUnit.SECONDS),
                "client must observe the server-side complete");
        assertNotNull(firstSentOfKind(sb.sent, WsProtocol.LISTEN_ENDED_TYPE),
                "complete must send listen-ended, sent: " + sb.sent);

        client.shutdown();
        server.shutdown();
    }

    @Test
    void connectionCloseCleansUpEventStreams() throws Exception {
        Loopback sa = new Loopback();
        Loopback sb = new Loopback();
        WsRpcChannel[] pair = pair(sa, sb);
        WsRpcChannel client = pair[0];
        WsRpcChannel server = pair[1];

        CountDownLatch clientCloseLatch = new CountDownLatch(1);
        CountDownLatch serverCloseLatch = new CountDownLatch(1);

        server.registerEventListener("usage", UsageParams.class, req -> {
            req.handle().onClose(serverCloseLatch::countDown);
            return CompletableFuture.completedFuture(null);
        });

        UUID listenId = UUID.randomUUID();
        client.onListenClose(listenId, clientCloseLatch::countDown);

        CompletableFuture<Void> ack = client.listen(listenId, "usage", new UsageParams("srv-123"), event -> {},
                Duration.ofSeconds(5));
        ack.get(5, TimeUnit.SECONDS);

        client.onClose(new RuntimeException("connection lost"));
        server.onClose(new RuntimeException("connection lost"));

        assertTrue(clientCloseLatch.await(5, TimeUnit.SECONDS), "client close callback should be invoked");
        assertTrue(serverCloseLatch.await(5, TimeUnit.SECONDS), "server close callback should be invoked");

        client.shutdown();
        server.shutdown();
    }

    @Test
    void listenWaitsForSession() throws Exception {
        WsRpcChannel client = WsRpcChannel.create();
        WsRpcChannel server = WsRpcChannel.create();
        Loopback sa = new Loopback();
        Loopback sb = new Loopback();
        sb.peer = client;
        server.onOpen(sb);

        server.registerEventListener("usage", UsageParams.class, req -> CompletableFuture.completedFuture(null));

        CompletableFuture<Void> ack = client.listen("usage", new UsageParams("srv-123"), event -> {},
                Duration.ofSeconds(5));

        assertFalse(ack.isDone(), "listen should wait for session");

        sa.peer = server;
        client.onOpen(sa);

        ack.get(5, TimeUnit.SECONDS);

        client.shutdown();
        server.shutdown();
    }

    @Test
    void listenTimeoutFailsFuture() {
        WsRpcChannel client = WsRpcChannel.create();
        Loopback sa = new Loopback();
        sa.dropListen = true;
        client.onOpen(sa);

        CompletableFuture<Void> ack = client.listen("usage", new UsageParams("srv-123"), event -> {},
                Duration.ofMillis(50));

        Exception ex = assertThrows(Exception.class, () -> ack.get(2, TimeUnit.SECONDS));
        assertTrue(ex.getCause() instanceof TimeoutException || ex instanceof TimeoutException);

        client.shutdown();
    }

    @Test
    void listenRejectedForUnknownEvent() {
        Loopback sa = new Loopback();
        Loopback sb = new Loopback();
        WsRpcChannel[] pair = pair(sa, sb);
        WsRpcChannel client = pair[0];
        WsRpcChannel server = pair[1];

        CompletableFuture<Void> ack = client.listen("ghost", new UsageParams("srv-123"), event -> {},
                Duration.ofSeconds(5));

        Exception err = assertThrows(Exception.class, () -> ack.get(5, TimeUnit.SECONDS));
        assertNotNull(err.getCause());
        assertTrue(err.getCause().getMessage().contains("unknown event"));

        client.shutdown();
        server.shutdown();
    }

    @Test
    void eventForUnknownListenerDropped() {
        WsRpcChannel server = WsRpcChannel.create();
        Loopback sb = new Loopback();
        server.onOpen(sb);

        String eventJson = "{\"id\":\"" + UUID.randomUUID() + "\",\"kind\":\"" + WsProtocol.EVENT_TYPE + "\""
                + ",\"event\":\"usage\",\"responseOf\":\"" + UUID.randomUUID()
                + "\",\"payload\":{\"cpu\":1}}";

        server.onTextMessage(eventJson);

        server.shutdown();
    }

    @Test
    void unlistenUnknownIdIsNoOp() {
        Loopback sa = new Loopback();
        Loopback sb = new Loopback();
        WsRpcChannel[] pair = pair(sa, sb);
        WsRpcChannel client = pair[0];

        int sentBefore = sa.sent.size();
        client.unlisten(UUID.randomUUID());

        assertEquals(sentBefore, sa.sent.size(), "no frame sent for unknown unlisten ID");

        pair[0].shutdown();
        pair[1].shutdown();
    }

    @Test
    void anyEventNameAcceptedIncludingKindValues() throws Exception {
        WsRpcChannel client = WsRpcChannel.create();
        WsRpcChannel server = WsRpcChannel.create();
        Loopback sa = new Loopback();
        Loopback sb = new Loopback();
        sa.peer = server;
        sb.peer = client;
        client.onOpen(sa);
        server.onOpen(sb);

        server.registerEventListener(WsProtocol.LISTEN_TYPE, String.class,
                req -> CompletableFuture.completedFuture(null));
        assertTrue(server.hasEventListener(WsProtocol.LISTEN_TYPE));

        CompletableFuture<Void> ack = client.listen(WsProtocol.LISTEN_TYPE, "params", event -> {},
                Duration.ofSeconds(5));
        ack.get(5, TimeUnit.SECONDS);

        server.unregisterEventListener(WsProtocol.LISTEN_TYPE);
        assertFalse(server.hasEventListener(WsProtocol.LISTEN_TYPE));

        client.shutdown();
        server.shutdown();
    }

    @Test
    void duplicateEventListenerRegistrationRejected() {
        WsRpcChannel server = WsRpcChannel.create();

        server.registerEventListener("usage", String.class, req -> CompletableFuture.completedFuture(null));
        assertTrue(server.hasEventListener("usage"));

        assertThrows(IllegalArgumentException.class,
                () -> server.registerEventListener("usage", String.class, req -> CompletableFuture.completedFuture(null)));

        server.unregisterEventListener("usage");
        assertFalse(server.hasEventListener("usage"));

        server.registerEventListener("usage", String.class, req -> CompletableFuture.completedFuture(null));
        assertTrue(server.hasEventListener("usage"));

        server.shutdown();
    }

    @Test
    void onCloseCallbacksInvokedOnUnlisten() throws Exception {
        Loopback sa = new Loopback();
        Loopback sb = new Loopback();
        WsRpcChannel[] pair = pair(sa, sb);
        WsRpcChannel client = pair[0];
        WsRpcChannel server = pair[1];

        CountDownLatch closeLatch = new CountDownLatch(1);
        server.registerEventListener("usage", UsageParams.class, req -> {
            req.handle().onClose(closeLatch::countDown);
            return CompletableFuture.completedFuture(null);
        });

        UUID listenId = UUID.randomUUID();
        CompletableFuture<Void> ack = client.listen(listenId, "usage", new UsageParams("srv-123"), event -> {},
                Duration.ofSeconds(5));
        ack.get(5, TimeUnit.SECONDS);

        client.unlisten(listenId);

        assertTrue(closeLatch.await(5, TimeUnit.SECONDS), "onClose callback not invoked on unlisten");

        client.shutdown();
        server.shutdown();
    }

    @Test
    void onCloseCallbacksInvokedOnServerFail() throws Exception {
        Loopback sa = new Loopback();
        Loopback sb = new Loopback();
        WsRpcChannel[] pair = pair(sa, sb);
        WsRpcChannel client = pair[0];
        WsRpcChannel server = pair[1];

        CountDownLatch closeLatch = new CountDownLatch(1);
        AtomicReference<PushHandle> handleRef = new AtomicReference<>();
        server.registerEventListener("usage", UsageParams.class, req -> {
            req.handle().onClose(closeLatch::countDown);
            handleRef.set(req.handle());
            return CompletableFuture.completedFuture(null);
        });

        CompletableFuture<Void> ack = client.listen("usage", new UsageParams("srv-123"), event -> {},
                Duration.ofSeconds(5));
        ack.get(5, TimeUnit.SECONDS);

        handleRef.get().fail("test fail");

        assertTrue(closeLatch.await(5, TimeUnit.SECONDS), "onClose callback not invoked on server fail");

        client.shutdown();
        server.shutdown();
    }

    @Test
    void onCloseCallbacksInvokedOnServerComplete() throws Exception {
        Loopback sa = new Loopback();
        Loopback sb = new Loopback();
        WsRpcChannel[] pair = pair(sa, sb);
        WsRpcChannel client = pair[0];
        WsRpcChannel server = pair[1];

        CountDownLatch closeLatch = new CountDownLatch(1);
        AtomicReference<PushHandle> handleRef = new AtomicReference<>();
        server.registerEventListener("usage", UsageParams.class, req -> {
            req.handle().onClose(closeLatch::countDown);
            handleRef.set(req.handle());
            return CompletableFuture.completedFuture(null);
        });

        CompletableFuture<Void> ack = client.listen("usage", new UsageParams("srv-123"), event -> {},
                Duration.ofSeconds(5));
        ack.get(5, TimeUnit.SECONDS);

        handleRef.get().complete();

        assertTrue(closeLatch.await(5, TimeUnit.SECONDS), "onClose callback not invoked on server complete");

        client.shutdown();
        server.shutdown();
    }

    @Test
    void onCloseCallbacksInvokedImmediatelyIfAlreadyClosed() throws Exception {
        Loopback sa = new Loopback();
        Loopback sb = new Loopback();
        WsRpcChannel[] pair = pair(sa, sb);
        WsRpcChannel client = pair[0];
        WsRpcChannel server = pair[1];

        AtomicReference<PushHandle> handleRef = new AtomicReference<>();
        server.registerEventListener("usage", UsageParams.class, req -> {
            handleRef.set(req.handle());
            return CompletableFuture.completedFuture(null);
        });

        CompletableFuture<Void> ack = client.listen("usage", new UsageParams("srv-123"), event -> {},
                Duration.ofSeconds(5));
        ack.get(5, TimeUnit.SECONDS);

        PushHandle handle = handleRef.get();
        handle.complete();

        CountDownLatch latch = new CountDownLatch(1);
        handle.onClose(latch::countDown);
        assertTrue(latch.await(1, TimeUnit.SECONDS), "onClose callback should run immediately when already closed");

        client.shutdown();
        server.shutdown();
    }

    @Test
    void closeDuringSessionWaitFailsListen() {
        WsRpcChannel client = WsRpcChannel.create();

        CompletableFuture<Void> ack = client.listen("usage", new UsageParams("srv-123"), event -> {},
                Duration.ofSeconds(5));

        client.onClose(new RuntimeException("failed to open session"));

        Exception ex = assertThrows(Exception.class, () -> ack.get(2, TimeUnit.SECONDS));
        assertNotNull(ex);

        client.shutdown();
    }

    @Test
    void onListenHandlerFailureRejectsListen() {
        Loopback sa = new Loopback();
        Loopback sb = new Loopback();
        WsRpcChannel[] pair = pair(sa, sb);
        WsRpcChannel client = pair[0];
        WsRpcChannel server = pair[1];

        server.registerEventListener("usage", UsageParams.class, req -> {
            throw new RuntimeException("cannot initialize event stream");
        });

        CompletableFuture<Void> ack = client.listen("usage", new UsageParams("srv-123"), event -> {},
                Duration.ofSeconds(5));

        Exception err = assertThrows(Exception.class, () -> ack.get(5, TimeUnit.SECONDS));
        assertNotNull(err.getCause());
        assertTrue(err.getCause().getMessage().contains("Event listener failed"));

        client.shutdown();
        server.shutdown();
    }
}
