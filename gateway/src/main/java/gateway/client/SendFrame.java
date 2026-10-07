package gateway.client;

import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.Objects;

public sealed interface SendFrame extends OutboundItem
        permits SendFrame.TextFrame, SendFrame.BinaryFrame, SendFrame.PingFrame, SendFrame.CloseFrame {

    void execute(SendContext ctx) throws Exception;

    static SendFrame text(String text) {
        Objects.requireNonNull(text, "text");
        return new TextFrame(text);
    }

    static SendFrame binary(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        return new BinaryFrame(bytes);
    }

    static SendFrame ping() {
        byte[] payload = new byte[8];
        ThreadLocalRandom.current().nextBytes(payload);
        return new PingFrame(payload);
    }

    static SendFrame close(int code, String reason) {
        return new CloseFrame(code, reason == null ? "" : reason);
    }

    record TextFrame(String text) implements SendFrame {

        public TextFrame {
            Objects.requireNonNull(text, "text");
        }

        @Override
        public void execute(SendContext ctx) throws Exception {
            WebSocket socket = ctx.socket();
            socket.sendText(text, true)
                    .get(ctx.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    record BinaryFrame(byte[] bytes) implements SendFrame {

        public BinaryFrame {
            Objects.requireNonNull(bytes, "bytes");
        }

        @Override
        public void execute(SendContext ctx) throws Exception {
            WebSocket socket = ctx.socket();
            socket.sendBinary(ByteBuffer.wrap(bytes), true)
                    .get(ctx.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    record PingFrame(byte[] payload) implements SendFrame {

        public PingFrame {
            Objects.requireNonNull(payload, "payload");
        }

        @Override
        public void execute(SendContext ctx) throws Exception {
            WebSocket socket = ctx.socket();
            socket.sendPing(ByteBuffer.wrap(payload))
                    .get(ctx.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    record CloseFrame(int code, String reason) implements SendFrame {

        public CloseFrame {
            reason = reason == null ? "" : reason;
        }

        @Override
        public void execute(SendContext ctx) throws Exception {
            WebSocket socket = ctx.socket();

            if (socket.isOutputClosed()) {
                return;
            }

            try {
                socket.sendClose(code, reason)
                        .get(ctx.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            } catch (Exception e) {
                try {
                    socket.abort();
                } catch (Exception ignored) {
                }
                throw e;
            }
        }
    }
}
