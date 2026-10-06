package gateway;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import gateway.rpc.WsRpcChannel;
import gateway.session.WsSession;

class WsRpcChannelTest {

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

    @Test
    void requestResponseCorrelation() throws Exception {
        WsRpcChannel a = WsRpcChannel.create();
        WsRpcChannel b = WsRpcChannel.create();
        Loopback sa = new Loopback();
        Loopback sb = new Loopback();
        sa.peer = b;
        sb.peer = a;
        a.onOpen(sa);
        b.onOpen(sb);

        b.registerHandler("echo", String.class, s -> "hello:" + s);

        CompletableFuture<String> res = a.sendRequest("echo", "world", String.class, Duration.ofSeconds(5));
        assertEquals("hello:world", res.get(5, TimeUnit.SECONDS));

        a.shutdown();
        b.shutdown();
    }

    @Test
    void timeoutCompletesExceptionally() {
        WsRpcChannel a = WsRpcChannel.create();
        Loopback sa = new Loopback();
        a.onOpen(sa); // no peer, never responds

        CompletableFuture<String> res = a.sendRequest("ghost", "x", String.class, Duration.ofMillis(100));
        assertThrows(Exception.class, () -> {
            try {
                res.get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                assertTrue(e.getCause() instanceof TimeoutException
                        || e instanceof TimeoutException
                        || (e.getCause() != null && e.getCause().getCause() instanceof TimeoutException));
                throw e;
            }
        });
        a.shutdown();
    }

    @Test
    void requestWaitsForSessionThenCorrelates() throws Exception {
        WsRpcChannel a = WsRpcChannel.create();
        WsRpcChannel b = WsRpcChannel.create();
        Loopback sa = new Loopback();
        Loopback sb = new Loopback();
        sa.peer = b;
        sb.peer = a;
        b.onOpen(sb);
        b.registerHandler("echo", String.class, s -> "hello:" + s);

        // No session on a yet: request suspends instead of failing fast.
        CompletableFuture<String> res =
                a.sendRequest("echo", "world", String.class, Duration.ofSeconds(5));
        assertFalse(res.isDone(), "request must wait for a session, not fail fast");

        a.onOpen(sa);
        assertEquals("hello:world", res.get(5, TimeUnit.SECONDS));

        a.shutdown();
        b.shutdown();
    }

    @Test
    void closeDuringSessionWaitFails() {
        WsRpcChannel a = WsRpcChannel.create();

        CompletableFuture<String> res =
                a.sendRequest("ghost", "x", String.class, Duration.ofMinutes(1));
        assertFalse(res.isDone());
        a.onClose(new RuntimeException("boom"));
        assertTrue(res.isCompletedExceptionally());
        try {
            res.get(5, TimeUnit.SECONDS);
            fail("expected close cause");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("boom")
                    || (e.getCause() != null && e.getCause().getMessage().contains("boom")),
                    "expected close cause, got: " + e);
        }

        a.shutdown();
    }

    @Test
    void notificationWaitsForSessionThenSends() {
        WsRpcChannel a = WsRpcChannel.create();
        WsRpcChannel b = WsRpcChannel.create();
        Loopback sa = new Loopback();
        Loopback sb = new Loopback();
        sa.peer = b;
        sb.peer = a;
        b.onOpen(sb);
        b.registerHandler("ping", String.class, s -> null);

        a.sendNotification("ping", "x");
        assertTrue(sa.sent.isEmpty(), "nothing can be sent before a session opens");

        a.onOpen(sa);
        assertFalse(sa.sent.isEmpty(), "queued notification must flush once the session opens");

        a.shutdown();
        b.shutdown();
    }

    @Test
    void notificationDroppedOnClose() {
        WsRpcChannel a = WsRpcChannel.create();
        Loopback sa = new Loopback();

        a.sendNotification("ping", "x");
        a.onClose(new RuntimeException("gone"));
        assertTrue(sa.sent.isEmpty(), "closed channel must not send");

        a.shutdown();
    }

    @Test
    void onOpenRejectsNullAndClosed() {
        WsRpcChannel a = WsRpcChannel.create();
        assertThrows(NullPointerException.class, () -> a.onOpen(null));

        Loopback closed = new Loopback();
        closed.close(1000, "gone");
        assertThrows(IllegalStateException.class, () -> a.onOpen(closed));

        a.shutdown();
    }

    @Test
    void onCloseFailsPending() {
        WsRpcChannel a = WsRpcChannel.create();
        Loopback sa = new Loopback();
        a.onOpen(sa);

        CompletableFuture<JsonNode> res = a.sendRequest("never", null, JsonNode.class, Duration.ofMinutes(1));
        assertFalse(res.isDone());
        a.onClose(new RuntimeException("closed"));
        assertTrue(res.isCompletedExceptionally());
        a.shutdown();
    }

    @Test
    void errorResponseCompletesExceptionally() throws Exception {
        WsRpcChannel a = WsRpcChannel.create();
        WsRpcChannel b = WsRpcChannel.create();
        Loopback sa = new Loopback();
        Loopback sb = new Loopback();
        sa.peer = b;
        sb.peer = a;
        a.onOpen(sa);
        b.onOpen(sb);

        b.registerHandler("boom", String.class, s -> {
            throw new IllegalStateException("kaput");
        });

        CompletableFuture<String> res = a.sendRequest("boom", "x", String.class, Duration.ofSeconds(5));
        Exception err = assertThrows(Exception.class, () -> res.get(5, TimeUnit.SECONDS));
        assertNotNull(err);
        a.shutdown();
        b.shutdown();
    }

    @Test
    void unknownTypeFailsFastWithNamedError() throws Exception {
        WsRpcChannel a = WsRpcChannel.create();
        WsRpcChannel b = WsRpcChannel.create();
        Loopback sa = new Loopback();
        Loopback sb = new Loopback();
        sa.peer = b;
        sb.peer = a;
        a.onOpen(sa);
        b.onOpen(sb);

        CompletableFuture<String> res = a.sendRequest("ghost-type", "x", String.class, Duration.ofSeconds(5));
        Exception err = assertThrows(Exception.class, () -> res.get(5, TimeUnit.SECONDS));
        assertTrue(err.getCause() != null
                && err.getCause().getMessage().contains("unknown RPC type: ghost-type"));

        assertFalse(sb.sent.isEmpty(), "expected an error frame back");
        ObjectMapper mapper = new ObjectMapper();
        JsonNode frame = mapper.readTree(sb.sent.get(sb.sent.size() - 1));
        assertTrue(frame.get("error").asBoolean(), "error frame must carry the error flag");
        assertTrue(frame.get("responseOf").isTextual(), "error frame must correlate the request");
        assertTrue(frame.get("payload").asText().contains("unknown RPC type: ghost-type"));

        a.shutdown();
        b.shutdown();
    }

    @Test
    void errorFrameNeverLoops() {
        WsRpcChannel b = WsRpcChannel.create();
        Loopback sb = new Loopback();
        b.onOpen(sb); // no peer needed: stray frames are dropped, never answered

        String stray = "{\"id\":\"" + UUID.randomUUID() + "\",\"type\":\"ghost\""
                + ",\"responseOf\":\"" + UUID.randomUUID() + "\""
                + ",\"error\":true,\"payload\":\"boom\"}";
        int before = sb.sent.size();
        assertDoesNotThrow(() -> b.onTextMessage(stray));
        assertEquals(before, sb.sent.size(), "an error frame must never trigger a reply");

        b.shutdown();
    }

    @Test
    void nestedPayloadRejected() {
        WsMessage<?> inner = WsMessage.create("inner").withPayload("data");

        assertThrows(IllegalArgumentException.class, () -> WsMessage.create("outer").withPayload(inner));
        assertThrows(IllegalArgumentException.class, () -> WsMessage.create("outer").setPayload(inner));
        assertThrows(IllegalArgumentException.class, () -> WsMessage.create("outer").response(inner));
        assertThrows(IllegalArgumentException.class, () -> WsMessage.create("outer").error(inner));
    }

    @Test
    void handlerThrowYieldsWireErrorFrame() throws Exception {
        WsRpcChannel a = WsRpcChannel.create();
        WsRpcChannel b = WsRpcChannel.create();
        Loopback sa = new Loopback();
        Loopback sb = new Loopback();
        sa.peer = b;
        sb.peer = a;
        a.onOpen(sa);
        b.onOpen(sb);

        b.registerHandler("boom-wire", String.class, s -> {
            throw new IllegalStateException("kaput-wire");
        });

        CompletableFuture<String> res = a.sendRequest("boom-wire", "x", String.class, Duration.ofSeconds(5));
        Exception err = assertThrows(Exception.class, () -> res.get(5, TimeUnit.SECONDS));
        assertNotNull(err);

        assertFalse(sa.sent.isEmpty(), "expected request frame");
        assertFalse(sb.sent.isEmpty(), "expected an error frame back");
        ObjectMapper mapper = new ObjectMapper();
        JsonNode request = mapper.readTree(sa.sent.get(sa.sent.size() - 1));
        JsonNode frame = mapper.readTree(sb.sent.get(sb.sent.size() - 1));
        assertTrue(frame.get("error").asBoolean(), "error frame must carry the error flag");
        assertEquals(request.get("id").asText(), frame.get("responseOf").asText(),
                "error frame must correlate the request");
        assertTrue(frame.get("payload").asText().contains("kaput-wire"));

        a.shutdown();
        b.shutdown();
    }
}
