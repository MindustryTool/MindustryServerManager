package gateway.rpc;


import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gateway.session.WsSession;
import gateway.stream.ChunkHeader;
import gateway.stream.ChunkWriter;
import gateway.subscription.SubscriptionHandle;
import gateway.wire.WsProtocol;

/**
 * Guards Layer 2: a reply, stream ack, or pushed event is bound to the
 * session that delivered the originating frame, never to the channel's
 * mutable current session.
 */
class SessionBindingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static class Loopback implements WsSession {
        final List<String> sent = new CopyOnWriteArrayList<>();
        volatile boolean open = true;

        @Override
        public void sendText(String text) {
            sent.add(text);
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

        boolean answered() {
            return sent.stream().anyMatch(t -> t.contains("\"kind\":\"" + WsProtocol.RESPONSE_TYPE + "\""));
        }

        boolean containsKind(String kind) {
            return sent.stream().anyMatch(t -> t.contains("\"kind\":\"" + kind + "\""));
        }
    }

    private static String requestJson(String type, Object payload) throws Exception {
        return MAPPER.writeValueAsString(
                gateway.wire.WsMessage.<Object>create(WsProtocol.REQUEST_TYPE).setType(type).withPayload(payload));
    }

    @Test
    void requestWithOpenOriginBeforeAdoptionStillGetsReply() throws Exception {
        RpcChannel b = RpcChannel.create();
        Loopback origin = new Loopback();
        try {
            b.registerHandler("echo", String.class, ctx -> "hi:" + ctx.body());

            // No onOpen: current is null, but the delivering session is open.
            b.onTextMessage(origin, requestJson("echo", "x"));

            assertTrue(origin.answered(), "reply must bind to the delivering session, sent: " + origin.sent);
        } finally {
            b.shutdown();
        }
    }

    @Test
    void replyIsNotReroutedToReplacementSession() throws Exception {
        RpcChannel b = RpcChannel.create();
        Loopback genA = new Loopback();
        Loopback genB = new Loopback();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            b.onOpen(genA);
            b.registerHandler("slow", String.class, _ctx -> {
                entered.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "done";
            });

            Thread worker = new Thread(() -> b.onTextMessage(genA, requestJsonUnchecked("slow", "x")));
            worker.start();
            assertTrue(entered.await(5, TimeUnit.SECONDS), "handler must start");

            b.onOpen(genB); // overwrite: closes genA with 4234
            release.countDown();
            worker.join(5000);

            assertTrue(genA.sent.isEmpty(), "closed origin must drop, got: " + genA.sent);
            assertTrue(genB.sent.isEmpty(), "replacement must never receive the answer, got: " + genB.sent);
        } finally {
            b.shutdown();
        }
    }

    @Test
    void replyDroppedWhenOriginClosed() throws Exception {
        RpcChannel b = RpcChannel.create();
        Loopback origin = new Loopback();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            b.onOpen(origin);
            b.registerHandler("slow", String.class, _ctx -> {
                entered.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "done";
            });

            Thread worker = new Thread(() -> b.onTextMessage(origin, requestJsonUnchecked("slow", "x")));
            worker.start();
            assertTrue(entered.await(5, TimeUnit.SECONDS), "handler must start");

            origin.open = false;
            release.countDown();
            worker.join(5000);

            assertTrue(origin.sent.isEmpty(), "closed origin must drop with no frame, got: " + origin.sent);
        } finally {
            b.shutdown();
        }
    }

    @Test
    void subscriptionAckAndPushBindToDeliveringSession() throws Exception {
        RpcChannel b = RpcChannel.create();
        Loopback current = new Loopback();
        Loopback origin = new Loopback();
        AtomicReference<SubscriptionHandle> handleRef = new AtomicReference<>();
        try {
            b.onOpen(current);
            b.registerEventListener("usage", String.class, req -> {
                handleRef.set(req.handle());
                return CompletableFuture.completedFuture(null);
            });

            gateway.wire.WsMessage<Object> listen = gateway.wire.WsMessage.<Object>create(WsProtocol.SUBSCRIBE_TYPE)
                    .setEvent("usage")
                    .withPayload(Map.of("data", "srv"));
            b.onTextMessage(origin, MAPPER.writeValueAsString(listen));

            assertTrue(origin.containsKind(WsProtocol.SUBSCRIBED_TYPE),
                    "listening ack must go to the deliverer, sent: " + origin.sent);
            assertTrue(current.sent.isEmpty(), "current must not receive the ack, got: " + current.sent);

            SubscriptionHandle handle = handleRef.get();
            assertNotNull(handle, "listener must be created");
            handle.push(Map.of("cpu", 1));

            assertTrue(origin.containsKind(WsProtocol.EVENT_TYPE),
                    "pushed event must go to the listener's session, sent: " + origin.sent);
            assertTrue(current.sent.isEmpty(), "current must not receive the push, got: " + current.sent);
        } finally {
            b.shutdown();
        }
    }

    @Test
    void streamAckBindsToDeliveringSession() throws Exception {
        RpcChannel b = RpcChannel.create();
        Loopback current = new Loopback();
        Loopback origin = new Loopback();
        try {
            b.onOpen(current);
            b.registerStreamHandler("doc", String.class, String.class, (meta, bytes) -> "ok");

            UUID streamId = UUID.randomUUID();
            byte[] payload = new byte[] { 1 };
            String sha = ChunkWriter.sha256Hex(payload);

            String startJson = MAPPER.writeValueAsString(gateway.wire.WsMessage.<Object>create(WsProtocol.STREAM_START_TYPE)
                    .setType("doc")
                    .withPayload(Map.of("streamId", streamId.toString(), "metadata", "m",
                            "totalChunks", 1, "sha256", sha)));
            b.onTextMessage(origin, startJson);
            b.onBinaryMessage(new ChunkHeader(streamId, 0).encodeFrame(payload, 0, payload.length));

            String doneJson = MAPPER.writeValueAsString(gateway.wire.WsMessage.<Object>create(WsProtocol.STREAM_DONE_TYPE)
                    .setType("doc")
                    .withPayload(Map.of("streamId", streamId.toString(), "sha256", sha)));
            b.onTextMessage(origin, doneJson);

            assertTrue(origin.answered(), "stream ack must go to the deliverer, sent: " + origin.sent);
            assertTrue(current.sent.isEmpty(), "current must not receive the ack, got: " + current.sent);
            assertEquals(0, b.pendingStreamCount());
        } finally {
            b.shutdown();
        }
    }

    @Test
    void handlerContextExposesDeliveringSessionAndSendsOnIt() throws Exception {
        RpcChannel b = RpcChannel.create();
        Loopback current = new Loopback();
        Loopback origin = new Loopback();
        try {
            b.onOpen(current);
            b.registerHandler("ctx-echo", String.class, ctx -> {
                ctx.send(gateway.wire.WsMessage.<Object>create(WsProtocol.NOTIFICATION_TYPE)
                        .setType("ctx-push")
                        .withPayload(ctx.session() == origin ? "ok" : "wrong"));
                return ctx.body();
            });

            b.onTextMessage(origin, requestJson("ctx-echo", "zzz"));

            assertTrue(origin.answered(), "reply must go to the delivering session, got: " + origin.sent);
            assertTrue(origin.containsKind(WsProtocol.NOTIFICATION_TYPE),
                    "context send must go to the delivering session, got: " + origin.sent);
            assertTrue(origin.sent.stream().anyMatch(t -> t.contains("zzz")),
                    "context body accessor must decode the request, got: " + origin.sent);
            assertTrue(current.sent.isEmpty(), "current must not receive the context send, got: " + current.sent);
        } finally {
            b.shutdown();
        }
    }

    @Test
    void handlerServesReplacementSessionWithoutReregistration() throws Exception {
        RpcChannel b = RpcChannel.create();
        Loopback first = new Loopback();
        Loopback replacement = new Loopback();
        try {
            b.registerHandler("echo", String.class, ctx -> "hi:" + ctx.body());

            b.onOpen(first);
            b.onTextMessage(first, requestJson("echo", "1"));
            assertTrue(first.answered(), "first session must be served");

            b.onOpen(replacement);
            b.onTextMessage(replacement, requestJson("echo", "2"));
            assertTrue(replacement.answered(), "replacement must be served by the same handler");
        } finally {
            b.shutdown();
        }
    }

    @Test
    void handlerRegistriesAreIndependentPerChannel() throws Exception {
        RpcChannel a = RpcChannel.create();
        RpcChannel b = RpcChannel.create();
        try {
            b.registerHandler("only-b", String.class, _ctx -> "b");
            assertTrue(b.hasHandler("only-b"), "registration must exist on its channel");
            assertTrue(!a.hasHandler("only-b"), "registration must not leak to another channel");
        } finally {
            a.shutdown();
            b.shutdown();
        }
    }

    private static String requestJsonUnchecked(String type, Object payload) {
        try {
            return requestJson(type, payload);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
