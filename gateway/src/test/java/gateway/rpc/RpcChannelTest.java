package gateway.rpc;


import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

import gateway.session.WsSession;
import gateway.wire.NoSessionException;
import gateway.wire.WsMessage;
import gateway.wire.WsProtocol;

class RpcChannelTest {

    static class Loopback implements WsSession {
        RpcChannel peer;
        final List<String> sent = new CopyOnWriteArrayList<>();
        volatile boolean open = true;
        volatile int lastCloseCode = -1;
        volatile String lastCloseReason;

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
            lastCloseCode = code;
            lastCloseReason = reason;
        }

        @Override
        public boolean isOpen() {
            return open;
        }
    }

    @Test
    void requestResponseCorrelation() throws Exception {
        RpcChannel a = RpcChannel.create();
        RpcChannel b = RpcChannel.create();
        Loopback sa = new Loopback();
        Loopback sb = new Loopback();
        sa.peer = b;
        sb.peer = a;
        a.onOpen(sa);
        b.onOpen(sb);

        b.registerHandler("echo", String.class, ctx -> "hello:" + ctx.body());

        CompletableFuture<String> res = a.sendRequest("echo", "world", String.class, Duration.ofSeconds(5));
        assertEquals("hello:world", res.get(5, TimeUnit.SECONDS));

        a.shutdown();
        b.shutdown();
    }

    @Test
    void requestFrameDeclaresKindAndType() throws Exception {
        RpcChannel a = RpcChannel.create();
        RpcChannel b = RpcChannel.create();
        Loopback sa = new Loopback();
        Loopback sb = new Loopback();
        sa.peer = b;
        sb.peer = a;
        a.onOpen(sa);
        b.onOpen(sb);

        b.registerHandler("echo", String.class, ctx -> "hello:" + ctx.body());

        CompletableFuture<String> res = a.sendRequest("echo", "world", String.class, Duration.ofSeconds(5));
        assertEquals("hello:world", res.get(5, TimeUnit.SECONDS));

        assertFalse(sa.sent.isEmpty(), "expected a request frame");
        ObjectMapper mapper = new ObjectMapper();
        JsonNode request = mapper.readTree(sa.sent.get(sa.sent.size() - 1));
        assertEquals(WsProtocol.REQUEST_TYPE, request.get("kind").asText());
        assertEquals("echo", request.get("type").asText());
        assertTrue(request.get("event").isNull(), "request must not carry an event field");
        assertTrue(request.get("responseOf").isNull(), "request must not carry responseOf");

        assertFalse(sb.sent.isEmpty(), "expected a response frame");
        JsonNode answer = mapper.readTree(sb.sent.get(sb.sent.size() - 1));
        assertEquals(WsProtocol.RESPONSE_TYPE, answer.get("kind").asText());
        assertEquals("echo", answer.get("type").asText());
        assertEquals(request.get("id").asText(), answer.get("responseOf").asText());

        a.shutdown();
        b.shutdown();
    }

    @Test
    void timeoutCompletesExceptionally() {
        RpcChannel a = RpcChannel.create();
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
    void requestFailsFastOfflineAndDoesNotReplay() {
        RpcChannel a = RpcChannel.create();
        RpcChannel b = RpcChannel.create();
        Loopback sa = new Loopback();
        Loopback sb = new Loopback();
        sa.peer = b;
        sb.peer = a;
        b.onOpen(sb);
        b.registerHandler("echo", String.class, ctx -> "hello:" + ctx.body());

        // No session on a yet: request fails fast instead of parking.
        CompletableFuture<String> res =
                a.sendRequest("echo", "world", String.class, Duration.ofSeconds(5));
        assertTrue(res.isCompletedExceptionally(), "offline request must fail fast");
        Exception err = assertThrows(Exception.class, () -> res.get(1, TimeUnit.SECONDS));
        assertInstanceOf(NoSessionException.class, err.getCause());
        assertEquals("echo", ((NoSessionException) err.getCause()).requestedType());

        // A later session must not transmit the abandoned request.
        a.onOpen(sa);
        assertTrue(sa.sent.isEmpty(), "offline request must not be replayed on a later session");

        a.shutdown();
        b.shutdown();
    }

    @Test
    void offlineFailureIsDistinctFromTimeout() {
        RpcChannel a = RpcChannel.create();

        CompletableFuture<String> offline =
                a.sendRequest("ghost", "x", String.class, Duration.ofMinutes(1));
        Exception offlineErr = assertThrows(Exception.class, () -> offline.get(1, TimeUnit.SECONDS));
        assertInstanceOf(NoSessionException.class, offlineErr.getCause());

        a.shutdown();
    }

    @Test
    void notificationDropsWhenNoSessionAndIsNotReplayed() {
        RpcChannel a = RpcChannel.create();
        RpcChannel b = RpcChannel.create();
        Loopback sa = new Loopback();
        Loopback sb = new Loopback();
        sa.peer = b;
        sb.peer = a;
        b.onOpen(sb);
        b.registerHandler("ping", String.class, _ctx -> null);

        a.sendNotification("ping", "x");
        assertTrue(sa.sent.isEmpty(), "nothing can be sent before a session opens");

        a.onOpen(sa);
        assertTrue(sa.sent.isEmpty(), "a dropped notification must not be replayed on the new session");

        a.shutdown();
        b.shutdown();
    }

    @Test
    void notificationDroppedOnClose() {
        RpcChannel a = RpcChannel.create();
        Loopback sa = new Loopback();

        a.sendNotification("ping", "x");
        a.onClose(new RuntimeException("gone"));
        assertTrue(sa.sent.isEmpty(), "closed channel must not send");

        a.shutdown();
    }

    @Test
    void notificationNeverAnsweredEvenWhenUnknown() throws Exception {
        RpcChannel a = RpcChannel.create();
        RpcChannel b = RpcChannel.create();
        Loopback sa = new Loopback();
        Loopback sb = new Loopback();
        sa.peer = b;
        sb.peer = a;
        a.onOpen(sa);
        b.onOpen(sb);

        CountDownLatch handled = new CountDownLatch(1);
        b.registerHandler("ping", String.class, _ctx -> {
            handled.countDown();
            return null;
        });

        a.sendNotification("ping", "x");
        assertTrue(handled.await(5, TimeUnit.SECONDS), "notification handler must run");
        assertTrue(sb.sent.isEmpty(), "a handled notification must never be answered");

        int sentBefore = sb.sent.size();
        a.sendNotification("ghost-notification", "x");
        Thread.sleep(200);
        assertEquals(sentBefore, sb.sent.size(), "an unknown notification must never be answered");

        ObjectMapper mapper = new ObjectMapper();
        JsonNode notification = mapper.readTree(sa.sent.get(sa.sent.size() - 1));
        assertEquals(WsProtocol.NOTIFICATION_TYPE, notification.get("kind").asText());

        a.shutdown();
        b.shutdown();
    }

    @Test
    void awaitSessionCompletesImmediatelyWhenOpen() throws Exception {
        RpcChannel a = RpcChannel.create();
        Loopback sa = new Loopback();
        a.onOpen(sa);

        assertSame(sa, a.awaitSession(Duration.ofSeconds(5)).get(5, TimeUnit.SECONDS));

        a.shutdown();
    }

    @Test
    void awaitSessionCompletesWhenSessionOpensLater() throws Exception {
        RpcChannel a = RpcChannel.create();
        Loopback sa = new Loopback();

        CompletableFuture<WsSession> waiting = a.awaitSession(Duration.ofSeconds(5));
        assertFalse(waiting.isDone(), "no session yet: poll must wait, not fail");

        a.onOpen(sa);
        assertSame(sa, waiting.get(5, TimeUnit.SECONDS));

        a.shutdown();
    }

    @Test
    void awaitSessionTimesOutWithoutSession() {
        RpcChannel a = RpcChannel.create();

        CompletableFuture<WsSession> waiting = a.awaitSession(Duration.ofMillis(100));
        Exception err = assertThrows(Exception.class, () -> waiting.get(5, TimeUnit.SECONDS));
        assertInstanceOf(TimeoutException.class, err.getCause());

        a.shutdown();
    }

    @Test
    void overwriteOnOpenClosesOldWith4234AndAdoptsNew() {
        RpcChannel a = RpcChannel.create();
        Loopback oldSession = new Loopback();
        Loopback newSession = new Loopback();
        a.onOpen(oldSession);
        assertSame(oldSession, a.current());

        assertDoesNotThrow(() -> a.onOpen(newSession));

        assertSame(newSession, a.current());
        assertFalse(oldSession.isOpen());
        assertEquals(WsProtocol.REPLACED_CLOSE_CODE, oldSession.lastCloseCode);
        assertTrue(newSession.isOpen());

        a.shutdown();
    }

    @Test
    void sameSessionOpenIsNoOp() {
        RpcChannel a = RpcChannel.create();
        Loopback session = new Loopback();
        a.onOpen(session);

        assertDoesNotThrow(() -> a.onOpen(session));

        assertSame(session, a.current());
        assertEquals(-1, session.lastCloseCode);

        a.shutdown();
    }

    @Test
    void staleCloseIsIgnored() {
        RpcChannel a = RpcChannel.create();
        Loopback oldSession = new Loopback();
        Loopback newSession = new Loopback();
        a.onOpen(oldSession);
        a.onOpen(newSession);

        assertFalse(a.onClose(oldSession, new RuntimeException("late")));

        assertSame(newSession, a.current());
        assertTrue(newSession.isOpen());

        a.shutdown();
    }

    @Test
    void postCloseSendFailsFastWithoutReplay() throws Exception {
        RpcChannel a = RpcChannel.create();
        RpcChannel b = RpcChannel.create();
        Loopback sa = new Loopback();
        Loopback sb = new Loopback();
        sa.peer = b;
        sb.peer = a;
        a.onOpen(sa);
        b.onOpen(sb);
        b.registerHandler("echo", String.class, ctx -> "hi:" + ctx.body());

        CompletableFuture<String> live =
                a.sendRequest("echo", "1", String.class, Duration.ofSeconds(5));
        assertEquals("hi:1", live.get(5, TimeUnit.SECONDS));

        a.onClose(new RuntimeException("blip"));
        assertNull(a.current());

        CompletableFuture<String> after =
                a.sendRequest("echo", "2", String.class, Duration.ofSeconds(5));
        assertTrue(after.isCompletedExceptionally(), "post-close send must fail fast");

        Loopback sa2 = new Loopback();
        sa2.peer = b;
        a.onOpen(sa2);
        Thread.sleep(100);
        assertTrue(sa2.sent.isEmpty(), "the failed request must not ride the new session");

        a.shutdown();
        b.shutdown();
    }

    @Test
    void dirtyReconnectWithoutCloseKeepsRequestsFlowing() throws Exception {
        RpcChannel a = RpcChannel.create();
        RpcChannel b = RpcChannel.create();
        Loopback sa1 = new Loopback();
        Loopback sb = new Loopback();
        sa1.peer = b;
        sb.peer = a;
        a.onOpen(sa1);
        b.onOpen(sb);
        b.registerHandler("echo", String.class, ctx -> "v:" + ctx.body());

        CompletableFuture<String> first =
                a.sendRequest("echo", "1", String.class, Duration.ofSeconds(5));
        assertEquals("v:1", first.get(5, TimeUnit.SECONDS));

        // Dirty death: no onClose, straight reopen must not throw.
        Loopback sa2 = new Loopback();
        sa2.peer = b;
        assertDoesNotThrow(() -> a.onOpen(sa2));

        assertFalse(sa1.isOpen());
        assertEquals(WsProtocol.REPLACED_CLOSE_CODE, sa1.lastCloseCode);

        CompletableFuture<String> second =
                a.sendRequest("echo", "2", String.class, Duration.ofSeconds(5));
        assertEquals("v:2", second.get(5, TimeUnit.SECONDS));

        a.shutdown();
        b.shutdown();
    }

    @Test
    void onOpenRejectsNullAndClosed() {        RpcChannel a = RpcChannel.create();
        assertThrows(NullPointerException.class, () -> a.onOpen(null));

        Loopback closed = new Loopback();
        closed.close(1000, "gone");
        assertThrows(IllegalStateException.class, () -> a.onOpen(closed));

        a.shutdown();
    }

    @Test
    void onCloseFailsPending() {
        RpcChannel a = RpcChannel.create();
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
        RpcChannel a = RpcChannel.create();
        RpcChannel b = RpcChannel.create();
        Loopback sa = new Loopback();
        Loopback sb = new Loopback();
        sa.peer = b;
        sb.peer = a;
        a.onOpen(sa);
        b.onOpen(sb);

        b.registerHandler("boom", String.class, _ctx -> {
            throw new IllegalStateException("kaput");
        });

        CompletableFuture<String> res = a.sendRequest("boom", "x", String.class, Duration.ofSeconds(5));
        Exception err = assertThrows(Exception.class, () -> res.get(5, TimeUnit.SECONDS));
        assertNotNull(err);
        a.shutdown();
        b.shutdown();
    }

    @Test
    void unknownTypeFailsFastWithNamedResponseError() throws Exception {
        RpcChannel a = RpcChannel.create();
        RpcChannel b = RpcChannel.create();
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

        assertFalse(sb.sent.isEmpty(), "expected a failure frame back");
        ObjectMapper mapper = new ObjectMapper();
        JsonNode frame = mapper.readTree(sb.sent.get(sb.sent.size() - 1));
        assertEquals(WsProtocol.RESPONSE_ERROR_TYPE, frame.get("kind").asText(),
                "failure must be a response-error kind");
        assertEquals("ghost-type", frame.get("type").asText(), "failure must echo the type");
        assertTrue(frame.get("responseOf").isTextual(), "failure frame must correlate the request");
        assertTrue(frame.get("payload").asText().contains("unknown RPC type: ghost-type"));
        assertFalse(frame.has("error"), "no error boolean may exist");

        a.shutdown();
        b.shutdown();
    }

    @Test
    void failureFrameNeverLoops() {
        RpcChannel b = RpcChannel.create();
        Loopback sb = new Loopback();
        b.onOpen(sb); // no peer needed: stray frames are dropped, never answered

        String stray = "{\"id\":\"" + UUID.randomUUID() + "\",\"kind\":\"" + WsProtocol.RESPONSE_ERROR_TYPE + "\""
                + ",\"type\":\"ghost\""
                + ",\"responseOf\":\"" + UUID.randomUUID() + "\""
                + ",\"payload\":\"boom\"}";
        int before = sb.sent.size();
        assertDoesNotThrow(() -> b.onTextMessage(b.current(), stray));
        assertEquals(before, sb.sent.size(), "a failure frame must never trigger a reply");

        b.shutdown();
    }

    @Test
    void nestedPayloadRejected() {
        WsMessage<?> inner = WsMessage.create(WsProtocol.REQUEST_TYPE).withPayload("data");

        assertThrows(IllegalArgumentException.class,
                () -> WsMessage.create(WsProtocol.REQUEST_TYPE).withPayload(inner));
        assertThrows(IllegalArgumentException.class,
                () -> WsMessage.create(WsProtocol.REQUEST_TYPE).setPayload(inner));
        assertThrows(IllegalArgumentException.class,
                () -> WsMessage.create(WsProtocol.REQUEST_TYPE).reply(WsProtocol.RESPONSE_TYPE, inner));
    }

    @Test
    void handlerThrowYieldsResponseErrorFrame() throws Exception {
        RpcChannel a = RpcChannel.create();
        RpcChannel b = RpcChannel.create();
        Loopback sa = new Loopback();
        Loopback sb = new Loopback();
        sa.peer = b;
        sb.peer = a;
        a.onOpen(sa);
        b.onOpen(sb);

        b.registerHandler("boom-wire", String.class, _ctx -> {
            throw new IllegalStateException("kaput-wire");
        });

        CompletableFuture<String> res = a.sendRequest("boom-wire", "x", String.class, Duration.ofSeconds(5));
        Exception err = assertThrows(Exception.class, () -> res.get(5, TimeUnit.SECONDS));
        assertNotNull(err);

        assertFalse(sa.sent.isEmpty(), "expected request frame");
        assertFalse(sb.sent.isEmpty(), "expected a failure frame back");
        ObjectMapper mapper = new ObjectMapper();
        JsonNode request = mapper.readTree(sa.sent.get(sa.sent.size() - 1));
        JsonNode frame = mapper.readTree(sb.sent.get(sb.sent.size() - 1));
        assertEquals(WsProtocol.RESPONSE_ERROR_TYPE, frame.get("kind").asText(),
                "failure must be a response-error kind");
        assertEquals(request.get("id").asText(), frame.get("responseOf").asText(),
                "failure frame must correlate the request");
        assertTrue(frame.get("payload").asText().contains("kaput-wire"));

        a.shutdown();
        b.shutdown();
    }
}
