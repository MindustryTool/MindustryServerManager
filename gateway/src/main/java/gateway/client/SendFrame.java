package gateway.client;

import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

public sealed interface SendFrame extends OutboundItem
        permits SendFrame.TextFrame, SendFrame.BinaryFrame, SendFrame.PingFrame, SendFrame.CloseFrame {

    void execute(SendContext ctx) throws Exception;

    CompletableFuture<WebSocket> executeAsync(SendContext ctx);

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
            executeAsync(ctx).get(ctx.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
        }

        @Override
        public CompletableFuture<WebSocket> executeAsync(SendContext ctx) {
            return ctx.socket().sendText(text, true);
        }
    }

    record BinaryFrame(byte[] bytes) implements SendFrame {

        public BinaryFrame {
            Objects.requireNonNull(bytes, "bytes");
        }

        @Override
        public void execute(SendContext ctx) throws Exception {
            executeAsync(ctx).get(ctx.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
        }

        @Override
        public CompletableFuture<WebSocket> executeAsync(SendContext ctx) {
            return ctx.socket().sendBinary(ByteBuffer.wrap(bytes), true);
        }
    }

    record PingFrame(byte[] payload) implements SendFrame {

        public PingFrame {
            Objects.requireNonNull(payload, "payload");
        }

        @Override
        public void execute(SendContext ctx) throws Exception {
            executeAsync(ctx).get(ctx.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
        }

        @Override
        public CompletableFuture<WebSocket> executeAsync(SendContext ctx) {
            return ctx.socket().sendPing(ByteBuffer.wrap(payload));
        }
    }

    record CloseFrame(int code, String reason) implements SendFrame {

        public CloseFrame {
            reason = reason == null ? "" : reason;
        }

        @Override
        public void execute(SendContext ctx) throws Exception {
            executeAsync(ctx).get(ctx.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
        }

        @Override
        public CompletableFuture<WebSocket> executeAsync(SendContext ctx) {
            WebSocket socket = ctx.socket();
            if (socket.isOutputClosed()) {
                return CompletableFuture.completedFuture(socket);
            }
            return socket.sendClose(code, reason).exceptionally(e -> {
                try {
                    socket.abort();
                } catch (Exception ignored) {
                }
                if (e instanceof RuntimeException re) {
                    throw re;
                }
                throw new CompletionException(e);
            });
        }
    }
}
