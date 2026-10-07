package gateway.rpc;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gateway.session.WsSession;
import gateway.wire.WsProtocol;

/**
 * Guards the v2 frame contract: kind is the sole discriminator, subject
 * fields are validated per kind, and notifications are never answered.
 */
class RpcFrameProtocolTest {

    static class Loopback implements WsSession {
        RpcChannel peer;
        final List<String> sent = new CopyOnWriteArrayList<>();
        volatile boolean open = true;

        @Override
        public void sendText(String text) {
            sent.add(text);
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

    private static RpcChannel[] pair(Loopback sa, Loopback sb) {
        RpcChannel a = RpcChannel.create();
        RpcChannel b = RpcChannel.create();
        sa.peer = b;
        sb.peer = a;
        a.onOpen(sa);
        b.onOpen(sb);
        return new RpcChannel[] { a, b };
    }

    @Test
    void unknownKindDroppedWithNoReply() {
        RpcChannel b = RpcChannel.create();
        Loopback sb = new Loopback();
        b.onOpen(sb);

        String frame = "{\"id\":\"" + UUID.randomUUID() + "\",\"kind\":\"teleport\""
                + ",\"type\":\"x\",\"payload\":1}";
        assertDoesNotThrow(() -> b.onTextMessage(b.current(), frame));
        assertTrue(sb.sent.isEmpty(), "unknown kind must stay silent, got: " + sb.sent);

        b.shutdown();
    }

    @Test
    void absentKindDroppedWithNoReply() {
        RpcChannel b = RpcChannel.create();
        Loopback sb = new Loopback();
        b.onOpen(sb);

        String frame = "{\"id\":\"" + UUID.randomUUID() + "\",\"type\":\"x\",\"payload\":1}";
        assertDoesNotThrow(() -> b.onTextMessage(b.current(), frame));
        assertTrue(sb.sent.isEmpty(), "absent kind must stay silent, got: " + sb.sent);

        b.shutdown();
    }

    @Test
    void requestWithoutTypeDroppedWithNoReply() {
        RpcChannel b = RpcChannel.create();
        Loopback sb = new Loopback();
        b.onOpen(sb);

        String frame = "{\"id\":\"" + UUID.randomUUID() + "\",\"kind\":\"" + WsProtocol.REQUEST_TYPE + "\""
                + ",\"payload\":1}";
        assertDoesNotThrow(() -> b.onTextMessage(b.current(), frame));
        assertTrue(sb.sent.isEmpty(), "request without type must stay silent, got: " + sb.sent);

        b.shutdown();
    }

    @Test
    void requestCarryingEventFieldDropped() {
        RpcChannel b = RpcChannel.create();
        Loopback sb = new Loopback();
        b.onOpen(sb);

        String frame = "{\"id\":\"" + UUID.randomUUID() + "\",\"kind\":\"" + WsProtocol.REQUEST_TYPE + "\""
                + ",\"type\":\"x\",\"event\":\"usage\",\"payload\":1}";
        assertDoesNotThrow(() -> b.onTextMessage(b.current(), frame));
        assertTrue(sb.sent.isEmpty(), "request carrying an event field must be dropped, got: " + sb.sent);

        b.shutdown();
    }

    @Test
    void eventFrameWithoutEventDropped() {
        RpcChannel b = RpcChannel.create();
        Loopback sb = new Loopback();
        b.onOpen(sb);

        String frame = "{\"id\":\"" + UUID.randomUUID() + "\",\"kind\":\"" + WsProtocol.EVENT_TYPE + "\""
                + ",\"responseOf\":\"" + UUID.randomUUID() + "\",\"payload\":1}";
        assertDoesNotThrow(() -> b.onTextMessage(b.current(), frame));
        assertTrue(sb.sent.isEmpty(), "event frame without event must be dropped, got: " + sb.sent);

        b.shutdown();
    }

    @Test
    void eventFrameCarryingTypeDropped() {
        RpcChannel b = RpcChannel.create();
        Loopback sb = new Loopback();
        b.onOpen(sb);

        String frame = "{\"id\":\"" + UUID.randomUUID() + "\",\"kind\":\"" + WsProtocol.EVENT_TYPE + "\""
                + ",\"type\":\"usage\",\"event\":\"usage\",\"responseOf\":\"" + UUID.randomUUID()
                + "\",\"payload\":1}";
        assertDoesNotThrow(() -> b.onTextMessage(b.current(), frame));
        assertTrue(sb.sent.isEmpty(), "event frame carrying a type must be dropped, got: " + sb.sent);

        b.shutdown();
    }

    @Test
    void responseWithoutResponseOfDroppedWithoutCrashing() {
        RpcChannel b = RpcChannel.create();
        Loopback sb = new Loopback();
        b.onOpen(sb);

        String frame = "{\"id\":\"" + UUID.randomUUID() + "\",\"kind\":\"" + WsProtocol.RESPONSE_TYPE + "\""
                + ",\"type\":\"x\",\"payload\":1}";
        assertDoesNotThrow(() -> b.onTextMessage(b.current(), frame));
        assertTrue(sb.sent.isEmpty(), "answer without responseOf must stay silent, got: " + sb.sent);

        b.shutdown();
    }

    @Test
    void notificationKindNeverAnswered() throws Exception {
        Loopback sa = new Loopback();
        Loopback sb = new Loopback();
        RpcChannel[] pair = pair(sa, sb);
        RpcChannel a = pair[0];
        RpcChannel b = pair[1];

        CountDownLatch handled = new CountDownLatch(1);
        b.registerHandler("ping", String.class, _ctx -> {
            handled.countDown();
            return null;
        });

        a.sendNotification("ping", "x");
        assertTrue(handled.await(5, TimeUnit.SECONDS), "notification handler must run");
        assertTrue(sb.sent.isEmpty(), "handled notification must never be answered");

        int sentBefore = sb.sent.size();
        a.sendNotification("ghost-type", "x");
        Thread.sleep(200);
        assertEquals(sentBefore, sb.sent.size(), "unknown notification must never be answered");

        a.shutdown();
        b.shutdown();
    }

    @Test
    void listenFrameRoutesByKindNotType() throws Exception {
        Loopback sa = new Loopback();
        Loopback sb = new Loopback();
        RpcChannel[] pair = pair(sa, sb);
        RpcChannel client = pair[0];
        RpcChannel server = pair[1];

        CountDownLatch listened = new CountDownLatch(1);
        server.registerEventListener("usage", String.class, req -> {
            listened.countDown();
            return CompletableFuture.completedFuture(null);
        });

        var ack = client.subscribe("usage", null, event -> {}, Duration.ofSeconds(5));
        assertTrue(listened.await(5, TimeUnit.SECONDS), "listen must route to the event listener");
        ack.get(5, TimeUnit.SECONDS);

        client.shutdown();
        server.shutdown();
    }
}
