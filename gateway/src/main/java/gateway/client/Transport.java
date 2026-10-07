package gateway.client;

import java.io.ByteArrayOutputStream;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import gateway.rpc.WsRpcChannel;
import gateway.session.WsSession;

/**
 * One physical socket and everything bound to it: the send queue, the
 * ping loop, inbound dispatch, and the {@link WsSession} handed to the
 * channel. A transport is attached at most once and terminates at most
 * once. Late listener events after termination are dropped.
 */
class Transport implements WsSession {

    private static final Logger LOG = Logger.getLogger(Transport.class.getName());

    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(10);
    private static final int QUEUE_WARN_DEPTH = 1000;

    /** Policy callbacks owned by the lifecycle. */
    interface Events {
        void onRemoteClose(Transport transport, int statusCode, String reason);

        void onTransportError(Transport transport, Throwable error);
    }

    private enum Kind {
        TEXT,
        BINARY,
        PING,
        CLOSE,
        POISON
    }

    private static final class SendOp {

        final Kind kind;
        final String text;
        final byte[] binary;
        final int closeCode;
        final String closeReason;
        final WebSocket closeTarget;

        private SendOp(
                Kind kind,
                String text,
                byte[] binary,
                int closeCode,
                String closeReason,
                WebSocket closeTarget) {

            this.kind = kind;
            this.text = text;
            this.binary = binary;
            this.closeCode = closeCode;
            this.closeReason = closeReason;
            this.closeTarget = closeTarget;
        }

        static SendOp text(String text) {
            return new SendOp(Kind.TEXT, text, null, 0, null, null);
        }

        static SendOp binary(byte[] bytes) {
            return new SendOp(Kind.BINARY, null, bytes, 0, null, null);
        }

        static SendOp ping() {
            return new SendOp(Kind.PING, null, null, 0, null, null);
        }

        static SendOp close(WebSocket target, int code, String reason) {
            return new SendOp(Kind.CLOSE, null, null, code, reason, target);
        }

        static SendOp poison() {
            return new SendOp(Kind.POISON, null, null, 0, null, null);
        }
    }

    private final ScheduledExecutorService scheduler;
    private final Duration pingInterval;
    private final Duration pongDeadline;
    private final WsRpcChannel channel;
    private final Events events;

    private volatile WebSocket socket;
    private final AtomicBoolean terminated = new AtomicBoolean(false);

    private final BlockingQueue<SendOp> sendQueue = new LinkedBlockingQueue<>();
    private volatile Thread senderThread;
    private volatile ScheduledFuture<?> pingTask;
    private volatile Instant lastPongAt = Instant.now();

    private final WebSocket.Listener listener = new InnerListener();

    Transport(
            ScheduledExecutorService scheduler,
            Duration pingInterval,
            Duration pongDeadline,
            WsRpcChannel channel,
            Events events) {

        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.pingInterval = Objects.requireNonNull(pingInterval, "pingInterval");
        this.pongDeadline = Objects.requireNonNull(pongDeadline, "pongDeadline");
        this.channel = Objects.requireNonNull(channel, "channel");
        this.events = Objects.requireNonNull(events, "events");
    }

    /** Listener to hand to the dial before the socket exists. */
    WebSocket.Listener listener() {
        return listener;
    }

    /** Bind the dialled socket and start sending. Called at most once. */
    void attach(WebSocket socket) {
        Objects.requireNonNull(socket, "socket");

        synchronized (this) {
            if (this.socket != null) {
                throw new IllegalStateException("Transport already attached");
            }

            if (terminated.get()) {
                throw new IllegalStateException("Transport terminated");
            }

            this.socket = socket;
        }

        ensureSenderRunning();
        startPingTask();
        socket.request(1);
    }

    /** Idempotent teardown: stop ping, drop queued sends, kill the sender. */
    void terminate() {
        if (!terminated.compareAndSet(false, true)) {
            return;
        }

        stopPingTask();
        sendQueue.clear();
        sendQueue.offer(SendOp.poison());
    }

    /**
     * Terminal close that first emits a close frame, then dies.
     * Used by client {@code close()}; never touches lifecycle state.
     */
    void shutdownGracefully(int code, String reason) {
        terminated.set(true);
        stopPingTask();

        WebSocket target = socket;

        if (target != null && !target.isOutputClosed()) {
            sendQueue.offer(SendOp.close(target, code, reason == null ? "" : reason));
        }

        sendQueue.offer(SendOp.poison());
    }

    boolean isTerminated() {
        return terminated.get();
    }

    int queueDepth() {
        return sendQueue.size();
    }

    boolean isSenderAlive() {
        Thread t = senderThread;
        return t != null && t.isAlive();
    }

    @Override
    public void sendText(String text) {
        Objects.requireNonNull(text, "text");

        try {
            enqueue(SendOp.text(text));
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "sendText failed", e);
            throw new IllegalStateException("WebSocket send failed", e);
        }
    }

    @Override
    public void sendBinary(ByteBuffer data) {
        Objects.requireNonNull(data, "data");

        ByteBuffer dup = data.duplicate();
        byte[] bytes = new byte[dup.remaining()];
        dup.get(bytes);

        try {
            enqueue(SendOp.binary(bytes));
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "sendBinary failed", e);
            throw new IllegalStateException("WebSocket send failed", e);
        }
    }

    @Override
    public void close(int code, String reason) {
        WebSocket target = socket;

        if (target == null) {
            throw new IllegalStateException("Transport has no socket");
        }

        try {
            if (!target.isOutputClosed()) {
                target.sendClose(code, reason == null ? "" : reason)
                        .get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            try {
                target.abort();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public boolean isOpen() {
        WebSocket target = socket;
        return !terminated.get()
                && target != null
                && !target.isOutputClosed()
                && !target.isInputClosed();
    }

    private void enqueue(SendOp op) {
        if (terminated.get()) {
            throw new IllegalStateException("Transport terminated");
        }

        WebSocket target = socket;

        if (target == null || target.isOutputClosed()) {
            throw new IllegalStateException("WebSocket is not open");
        }

        sendQueue.offer(op);

        int depth = sendQueue.size();

        if (depth >= QUEUE_WARN_DEPTH && depth % QUEUE_WARN_DEPTH == 0) {
            LOG.warning("Transport send queue depth " + depth);
        }
    }

    private synchronized void ensureSenderRunning() {
        Thread t = senderThread;

        if (t != null && t.isAlive()) {
            return;
        }

        Thread next = new Thread(this::senderLoop, "jdk-ws-sender");
        next.setDaemon(true);
        senderThread = next;
        next.start();
    }

    private void senderLoop() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                SendOp op = sendQueue.take();

                if (op.kind == Kind.POISON) {
                    return;
                }

                if (op.kind == Kind.CLOSE) {
                    sendCloseFrame(op);
                    return;
                }

                if (terminated.get()) {
                    continue;
                }

                WebSocket ws = socket;

                if (ws == null || ws.isOutputClosed()) {
                    continue;
                }

                switch (op.kind) {
                    case TEXT -> sendTextFrame(ws, op.text);
                    case BINARY -> sendBinaryFrame(ws, op.binary);
                    case PING -> sendPingFrame(ws);
                    default -> {
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void sendTextFrame(WebSocket ws, String text) {
        try {
            ws.sendText(text, true).get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "sendText failed", e);
            events.onTransportError(this, rootCause(e));
        }
    }

    private void sendBinaryFrame(WebSocket ws, byte[] bytes) {
        try {
            ws.sendBinary(ByteBuffer.wrap(bytes), true).get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "sendBinary failed", e);
            events.onTransportError(this, rootCause(e));
        }
    }

    private void sendPingFrame(WebSocket ws) {
        try {
            ByteBuffer ping = ByteBuffer.wrap(new byte[8]);
            ThreadLocalRandom.current().nextBytes(ping.array());

            ws.sendPing(ping).get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOG.log(Level.FINE, "Ping failed", e);
            events.onTransportError(this, rootCause(e));
        }
    }

    private void sendCloseFrame(SendOp op) {
        WebSocket target = op.closeTarget;

        if (target == null || target.isOutputClosed()) {
            return;
        }

        try {
            target.sendClose(
                    op.closeCode,
                    op.closeReason == null ? "" : op.closeReason)
                    .get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            try {
                target.abort();
            } catch (Exception ignored) {
            }
        }
    }

    private static Throwable rootCause(Throwable e) {
        Throwable cause = e;

        while (cause instanceof ExecutionException && cause.getCause() != null) {
            cause = cause.getCause();
        }

        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }

        return cause;
    }

    private synchronized void startPingTask() {
        stopPingTask();

        if (scheduler.isShutdown()) {
            return;
        }

        pingTask = scheduler.scheduleWithFixedDelay(
                () -> {
                    try {
                        if (terminated.get()) {
                            return;
                        }

                        WebSocket ws = socket;

                        if (ws == null) {
                            return;
                        }

                        boolean outputDead = ws.isOutputClosed();
                        boolean pongLate = Instant.now().isAfter(lastPongAt.plus(pongDeadline));

                        if (outputDead || pongLate) {
                            String why = outputDead ? "output closed" : "pong timeout";
                            LOG.warning("Transport dead (" + why + "), dropping transport");

                            try {
                                ws.abort();
                            } catch (Exception ignored) {
                            }

                            events.onTransportError(this, new RuntimeException("transport dead: " + why));
                            return;
                        }

                        sendQueue.offer(SendOp.ping());
                    } catch (Exception e) {
                        LOG.log(Level.FINE, "Ping task error", e);
                    }
                },
                pingInterval.toMillis(),
                pingInterval.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    private synchronized void stopPingTask() {
        if (pingTask != null) {
            pingTask.cancel(false);
            pingTask = null;
        }
    }

    private class InnerListener implements WebSocket.Listener {

        private final StringBuilder textAcc = new StringBuilder();
        private ByteArrayOutputStream binAcc;

        @Override
        public void onOpen(WebSocket webSocket) {
            if (terminated.get()) {
                return;
            }

            WebSocket target = socket;

            if (target != null) {
                target.request(1);
            }
        }

        @Override
        public CompletionStage<?> onText(
                WebSocket webSocket,
                CharSequence data,
                boolean last) {

            if (terminated.get()) {
                return CompletableFuture.completedFuture(null);
            }

            textAcc.append(data);
            webSocket.request(1);

            if (last) {
                String full = textAcc.toString();
                textAcc.setLength(0);

                try {
                    channel.onTextMessage(full);
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "RPC dispatch failed", e);
                }
            }

            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onBinary(
                WebSocket webSocket,
                ByteBuffer data,
                boolean last) {

            if (terminated.get()) {
                return CompletableFuture.completedFuture(null);
            }

            try {
                if (binAcc == null) {
                    binAcc = new ByteArrayOutputStream();
                }

                byte[] part = new byte[data.remaining()];
                data.get(part);

                binAcc.write(part, 0, part.length);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Binary accumulate failed", e);
            }

            webSocket.request(1);

            if (last) {
                ByteArrayOutputStream done = binAcc;
                binAcc = null;

                if (done != null) {
                    try {
                        channel.onBinaryMessage(ByteBuffer.wrap(done.toByteArray()));
                    } catch (Exception e) {
                        LOG.log(Level.WARNING, "Stream ingest failed", e);
                    }
                }
            }

            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onPing(
                WebSocket webSocket,
                ByteBuffer message) {

            if (terminated.get()) {
                return CompletableFuture.completedFuture(null);
            }

            webSocket.request(1);

            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onPong(
                WebSocket webSocket,
                ByteBuffer message) {

            if (terminated.get()) {
                return CompletableFuture.completedFuture(null);
            }

            lastPongAt = Instant.now();
            webSocket.request(1);

            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(
                WebSocket webSocket,
                int statusCode,
                String reason) {

            if (terminated.get()) {
                return CompletableFuture.completedFuture(null);
            }

            events.onRemoteClose(Transport.this, statusCode, reason);

            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            if (terminated.get()) {
                return;
            }

            LOG.log(Level.WARNING, "WebSocket error: " + error.getMessage(), error);

            try {
                webSocket.abort();
            } catch (Exception ignored) {
            }

            events.onTransportError(Transport.this, error);
        }
    }
}
