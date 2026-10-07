package gateway;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import gateway.rpc.WsProtocol;
import gateway.rpc.WsRpcChannel;
import gateway.session.WsSession;

/**
 * Guards the v2 frame contract: kind is the sole discriminator, subject
 * fields are validated per kind, and notifications are never answered.
 */
class WsRpcFrameProtocolTest {

    static class Loopback implements WsSession {
        WsRpcChannel peer;
        final List<String> sent = new CopyOnWriteArrayList<>();
        volatile boolean open = true;

        @Override
        public void sendText(String text) {
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

    @Test
    void unknownKindDroppedWithNoReply() {
        WsRpcChannel b = WsRpcChannel.create();
        Loopback sb = new Loopback();
        b.onOpen(sb);

        String frame = "{\"id\":\"" + UUID.randomUUID() + "\",\"kind\":\"teleport\""
                + ",\"type\":\"x\",\"payload\":1}";
        assertDoesNotThrow(() -> b.onTextMessage(frame));
        assertTrue(sb.sent.isEmpty(), "unknown kind must stay silent, got: " + sb.sent);

        b.shutdown();
    }

    @Test
    void absentKindDroppedWithNoReply() {
        WsRpcChannel b = WsRpcChannel.create();
        Loopback sb = new Loopback();
        b.onOpen(sb);

        String frame = "{\"id\":\"" + UUID.randomUUID() + "\",\"type\":\"x\",\"payload\":1}";
        assertDoesNotThrow(() -> b.onTextMessage(frame));
        assertTrue(sb.sent.isEmpty(), "absent kind must stay silent, got: " + sb.sent);

        b.shutdown();
    }

    @Test
    void requestWithoutTypeDroppedWithNoReply() {
        WsRpcChannel b = WsRpcChannel.create();
        Loopback sb = new Loopback();
        b.onOpen(sb);

        String frame = "{\"id\":\"" + UUID.randomUUID() + "\",\"kind\":\"" + WsProtocol.REQUEST_TYPE + "\""
                + ",\"payload\":1}";
        assertDoesNotThrow(() -> b.onTextMessage(frame));
        assertTrue(sb.sent.isEmpty(), "request without type must stay silent, got: " + sb.sent);

        b.shutdown();
    }

    @Test
    void requestCarryingEventFieldDropped() {
        WsRpcChannel b = WsRpcChannel.create();
        Loopback sb = new Loopback();
        b.onOpen(sb);

        String frame = "{\"id\":\"" + UUID.randomUUID() + "\",\"kind\":\"" + WsProtocol.REQUEST_TYPE + "\""
                + ",\"type\":\"x\",\"event\":\"usage\",\"payload\":1}";
        assertDoesNotThrow(() -> b.onTextMessage(frame));
        assertTrue(sb.sent.isEmpty(), "request carrying an event field must be dropped, got: " + sb.sent);

        b.shutdown();
    }

    @Test
    void eventFrameWithoutEventDropped() {
        WsRpcChannel b = WsRpcChannel.create();
        Loopback sb = new Loopback();
        b.onOpen(sb);

        String frame = "{\"id\":\"" + UUID.randomUUID() + "\",\"kind\":\"" + WsProtocol.EVENT_TYPE + "\""
                + ",\"responseOf\":\"" + UUID.randomUUID() + "\",\"payload\":1}";
        assertDoesNotThrow(() -> b.onTextMessage(frame));
        assertTrue(sb.sent.isEmpty(), "event frame without event must be dropped, got: " + sb.sent);

        b.shutdown();
    }

    @Test
    void eventFrameCarryingTypeDropped() {
        WsRpcChannel b = WsRpcChannel.create();
        Loopback sb = new Loopback();
        b.onOpen(sb);

        String frame = "{\"id\":\"" + UUID.randomUUID() + "\",\"kind\":\"" + WsProtocol.EVENT_TYPE + "\""
                + ",\"type\":\"usage\",\"event\":\"usage\",\"responseOf\":\"" + UUID.randomUUID()
                + "\",\"payload\":1}";
        assertDoesNotThrow(() -> b.onTextMessage(frame));
        assertTrue(sb.sent.isEmpty(), "event frame carrying a type must be dropped, got: " + sb.sent);

        b.shutdown();
    }

    @Test
    void responseWithoutResponseOfDroppedWithoutCrashing() {
        WsRpcChannel b = WsRpcChannel.create();
        Loopback sb = new Loopback();
        b.onOpen(sb);

        String frame = "{\"id\":\"" + UUID.randomUUID() + "\",\"kind\":\"" + WsProtocol.RESPONSE_TYPE + "\""
                + ",\"type\":\"x\",\"payload\":1}";
        assertDoesNotThrow(() -> b.onTextMessage(frame));
        assertTrue(sb.sent.isEmpty(), "answer without responseOf must stay silent, got: " + sb.sent);

        b.shutdown();
    }

    @Test
    void notificationKindNeverAnswered() throws Exception {
        Loopback sa = new Loopback();
        Loopback sb = new Loopback();
        WsRpcChannel[] pair = pair(sa, sb);
        WsRpcChannel a = pair[0];
        WsRpcChannel b = pair[1];

        CountDownLatch handled = new CountDownLatch(1);
        b.registerHandler("ping", String.class, s -> {
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
        WsRpcChannel[] pair = pair(sa, sb);
        WsRpcChannel client = pair[0];
        WsRpcChannel server = pair[1];

        CountDownLatch listened = new CountDownLatch(1);
        server.registerEventListener("usage", String.class, req -> {
            listened.countDown();
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        });

        var ack = client.listen("usage", null, event -> {}, java.time.Duration.ofSeconds(5));
        assertTrue(listened.await(5, TimeUnit.SECONDS), "listen must route to the event listener");
        ack.get(5, TimeUnit.SECONDS);

        client.shutdown();
        server.shutdown();
    }
}
