package gateway;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

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
        WsRpcChannel a = new WsRpcChannel();
        WsRpcChannel b = new WsRpcChannel();
        Loopback sa = new Loopback();
        Loopback sb = new Loopback();
        sa.peer = b;
        sb.peer = a;
        a.setSession(sa);
        b.setSession(sb);

        b.registerHandler("echo", String.class, s -> "hello:" + s);

        CompletableFuture<String> res = a.sendRequest("echo", "world", String.class, Duration.ofSeconds(5));
        assertEquals("hello:world", res.get(5, TimeUnit.SECONDS));

        a.shutdown();
        b.shutdown();
    }

    @Test
    void timeoutCompletesExceptionally() {
        WsRpcChannel a = new WsRpcChannel();
        Loopback sa = new Loopback();
        a.setSession(sa); // no peer, never responds

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
    void onCloseFailsPending() {
        WsRpcChannel a = new WsRpcChannel();
        Loopback sa = new Loopback();
        a.setSession(sa);

        CompletableFuture<JsonNode> res = a.sendRequest("never", null, JsonNode.class, Duration.ofMinutes(1));
        assertFalse(res.isDone());
        a.onClose(new RuntimeException("closed"));
        assertTrue(res.isCompletedExceptionally());
        a.shutdown();
    }

    @Test
    void errorResponseCompletesExceptionally() throws Exception {
        WsRpcChannel a = new WsRpcChannel();
        WsRpcChannel b = new WsRpcChannel();
        Loopback sa = new Loopback();
        Loopback sb = new Loopback();
        sa.peer = b;
        sb.peer = a;
        a.setSession(sa);
        b.setSession(sb);

        b.registerHandler("boom", String.class, s -> {
            throw new IllegalStateException("kaput");
        });

        CompletableFuture<String> res = a.sendRequest("boom", "x", String.class, Duration.ofSeconds(5));
        Exception err = assertThrows(Exception.class, () -> res.get(5, TimeUnit.SECONDS));
        assertNotNull(err);
        a.shutdown();
        b.shutdown();
    }
}
