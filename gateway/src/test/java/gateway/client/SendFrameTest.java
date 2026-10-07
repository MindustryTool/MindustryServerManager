package gateway.client;

import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SendFrameTest {

    static class RecordingFake implements WebSocket {
        final AtomicReference<String> text = new AtomicReference<>();
        final AtomicReference<byte[]> binary = new AtomicReference<>();
        final AtomicReference<ByteBuffer> ping = new AtomicReference<>();
        final AtomicReference<Integer> closeCode = new AtomicReference<>();
        final AtomicReference<String> closeReason = new AtomicReference<>();
        final AtomicInteger closes = new AtomicInteger(0);
        final AtomicBoolean failSends = new AtomicBoolean(false);
        volatile boolean outputClosed = false;

        private <T> CompletableFuture<T> result(T value) {
            if (failSends.get()) {
                CompletableFuture<T> failed = new CompletableFuture<>();
                failed.completeExceptionally(new RuntimeException("boom"));
                return failed;
            }
            return CompletableFuture.completedFuture(value);
        }

        @Override
        public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
            text.set(data.toString());
            return result(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) {
            ByteBuffer dup = data.duplicate();
            byte[] bytes = new byte[dup.remaining()];
            dup.get(bytes);
            binary.set(bytes);
            return result(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendPing(ByteBuffer message) {
            ByteBuffer dup = message.duplicate();
            byte[] bytes = new byte[dup.remaining()];
            dup.get(bytes);
            ping.set(ByteBuffer.wrap(bytes));
            return result(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendPong(ByteBuffer message) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
            closes.incrementAndGet();
            closeCode.set(statusCode);
            closeReason.set(reason);
            return result(this);
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

    private static SendContext ctx(RecordingFake fake) {
        return new SendContext(fake, Duration.ofSeconds(10), new StubEvents());
    }

    static class StubEvents implements WebSocketConnection.Events {
        @Override
        public void onRemoteClose(WebSocketConnection transport, int statusCode, String reason) {
        }

        @Override
        public void onTransportError(WebSocketConnection transport, Throwable error) {
        }
    }

    @Test
    void textDispatchesPayload() throws Exception {
        RecordingFake fake = new RecordingFake();
        SendFrame.text("hi").execute(ctx(fake));
        assertEquals("hi", fake.text.get());
    }

    @Test
    void binaryDispatchesOwnedBytes() throws Exception {
        RecordingFake fake = new RecordingFake();
        SendFrame.binary(new byte[]{1, 2, 3}).execute(ctx(fake));
        assertArrayEquals(new byte[]{1, 2, 3}, fake.binary.get());
    }

    @Test
    void pingCarriesEightBytes() throws Exception {
        RecordingFake fake = new RecordingFake();
        SendFrame.PingFrame ping = (SendFrame.PingFrame) SendFrame.ping();
        assertEquals(8, ping.payload().length);
        ping.execute(ctx(fake));
        assertTrue(fake.ping.get() != null);
    }

    @Test
    void closeNormalizesNullReason() throws Exception {
        RecordingFake fake = new RecordingFake();
        SendFrame.close(1000, null).execute(ctx(fake));
        assertEquals(1, fake.closes.get());
        assertEquals("", fake.closeReason.get());
    }

    @Test
    void closeSkipsWhenOutputClosed() throws Exception {
        RecordingFake fake = new RecordingFake();
        fake.outputClosed = true;
        SendFrame.close(1000, "bye").execute(ctx(fake));
        assertEquals(0, fake.closes.get());
    }

    @Test
    void failedSendThrowsForLoopMapping() {
        RecordingFake fake = new RecordingFake();
        fake.failSends.set(true);
        assertThrows(Exception.class, () -> SendFrame.text("hi").execute(ctx(fake)));
    }

    @Test
    void factoriesRejectNull() {
        assertThrows(NullPointerException.class, () -> SendFrame.text(null));
        assertThrows(NullPointerException.class, () -> SendFrame.binary(null));
        assertThrows(NullPointerException.class, () -> new SendContext(null, Duration.ofSeconds(1), new StubEvents()));
    }

    @Test
    void controlIsSingleQueueSignal() {
        assertSame(PoisonPill.POISON, PoisonPill.poison());
    }

    @Test
    void contextHoldsInjectedTimeout() {
        RecordingFake fake = new RecordingFake();
        Duration custom = Duration.ofMillis(250);
        SendContext context = new SendContext(fake, custom, new StubEvents());
        assertEquals(custom, context.sendTimeout());
        assertEquals(Duration.ofSeconds(10), WebSocketConnection.DEFAULT_SEND_TIMEOUT);
    }
}
