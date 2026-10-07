package gateway.client;

import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

public sealed interface SendOp extends QueueItem
        permits SendOp.TextOp, SendOp.BinaryOp, SendOp.PingOp, SendOp.CloseOp {

    void execute(SendContext ctx) throws Exception;

    static SendOp text(String text) {
        Objects.requireNonNull(text, "text");
        return new TextOp(text);
    }

    static SendOp binary(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        return new BinaryOp(bytes);
    }

    static SendOp ping() {
        byte[] payload = new byte[8];
        ThreadLocalRandom.current().nextBytes(payload);
        return new PingOp(payload);
    }

    static SendOp close(int code, String reason) {
        return new CloseOp(code, reason == null ? "" : reason);
    }

    record TextOp(String text) implements SendOp {

        public TextOp {
            Objects.requireNonNull(text, "text");
        }

        @Override
        public void execute(SendContext ctx) throws Exception {
            WebSocket socket = ctx.socket();
            socket.sendText(text, true)
                    .get(ctx.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    record BinaryOp(byte[] bytes) implements SendOp {

        public BinaryOp {
            Objects.requireNonNull(bytes, "bytes");
        }

        @Override
        public void execute(SendContext ctx) throws Exception {
            WebSocket socket = ctx.socket();
            socket.sendBinary(ByteBuffer.wrap(bytes), true)
                    .get(ctx.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    record PingOp(byte[] payload) implements SendOp {

        public PingOp {
            Objects.requireNonNull(payload, "payload");
        }

        @Override
        public void execute(SendContext ctx) throws Exception {
            WebSocket socket = ctx.socket();
            socket.sendPing(ByteBuffer.wrap(payload))
                    .get(ctx.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    record CloseOp(int code, String reason) implements SendOp {

        public CloseOp {
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
