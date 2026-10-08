package gateway.client;


import java.io.ByteArrayOutputStream;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.Objects;

import gateway.rpc.RpcChannel;
import gateway.session.WsSession;
import gateway.util.Causes;

/**
 * One physical socket and everything bound to it: the send queue, the
 * ping loop, inbound dispatch, and the {@link WsSession} handed to the
 * channel. A transport is attached at most once and terminates at most
 * once. Late listener events after termination are dropped.
 */
class WebSocketConnection implements WsSession {

    private static final Logger LOG = Logger.getLogger(WebSocketConnection.class.getName());

    static final Duration DEFAULT_SEND_TIMEOUT = Duration.ofSeconds(10);
    private static final int QUEUE_WARN_DEPTH = 1000;

    /** Policy callbacks owned by the lifecycle. */
    interface Events {
        void onRemoteClose(WebSocketConnection transport, int statusCode, String reason);

        void onTransportError(WebSocketConnection transport, Throwable error);
    }

    private final ScheduledExecutorService scheduler;
    private final Duration pingInterval;
    private final Duration pongDeadline;
    private final Duration sendTimeout;
    private final RpcChannel channel;
    private final Events events;

    private volatile WebSocket socket;
    private final AtomicBoolean terminated = new AtomicBoolean(false);
    private final AtomicBoolean readStarted = new AtomicBoolean(false);

    private final BlockingQueue<OutboundItem> sendQueue = new LinkedBlockingQueue<>();
    private volatile Thread senderThread;
    private volatile ScheduledFuture<?> pingTask;
    private volatile Instant lastPongAt = Instant.now();

    private final WebSocket.Listener listener = new InnerListener();

    WebSocketConnection(
            ScheduledExecutorService scheduler,
            Duration pingInterval,
            Duration pongDeadline,
            Duration sendTimeout,
            RpcChannel channel,
            Events events) {

        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.pingInterval = Objects.requireNonNull(pingInterval, "pingInterval");
        this.pongDeadline = Objects.requireNonNull(pongDeadline, "pongDeadline");
        this.sendTimeout = Objects.requireNonNull(sendTimeout, "sendTimeout");
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
                throw new IllegalStateException("WebSocketConnection already attached");
            }

            if (terminated.get()) {
                throw new IllegalStateException("WebSocketConnection terminated");
            }

            this.socket = socket;
        }

        ensureSenderRunning();
        startPingTask();
    }

    /**
     * Grant the first inbound read. Called only after the channel has adopted
     * this transport as its session, so no frame can reach dispatch before
     * adoption. Idempotent.
     */
    void beginRead() {
        if (!readStarted.compareAndSet(false, true)) {
            return;
        }
        WebSocket target = socket;

        if (target != null && !terminated.get()) {
            target.request(1);
        }
    }

    /** Idempotent teardown: stop ping, drop queued sends, kill the sender. */
    void terminate() {
        if (!terminated.compareAndSet(false, true)) {
            return;
        }

        stopPingTask();
        sendQueue.clear();
        sendQueue.offer(PoisonPill.poison());
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
            sendQueue.offer(SendFrame.close(code, reason));
        }

        sendQueue.offer(PoisonPill.poison());
    }

    int queueDepth() {
        return sendQueue.size();
    }

    @Override
    public void sendText(String text) {
        Objects.requireNonNull(text, "text");

        try {
            enqueue(SendFrame.text(text));
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
            enqueue(SendFrame.binary(bytes));
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
            throw new IllegalStateException("WebSocketConnection has no socket");
        }

        try {
            if (!target.isOutputClosed()) {
                target.sendClose(code, reason == null ? "" : reason)
                        .get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
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

    private void enqueue(OutboundItem op) {
        if (terminated.get()) {
            throw new IllegalStateException("WebSocketConnection terminated");
        }

        WebSocket target = socket;

        if (target == null || target.isOutputClosed()) {
            throw new IllegalStateException("WebSocket is not open");
        }

        sendQueue.offer(op);

        int depth = sendQueue.size();

        if (depth >= QUEUE_WARN_DEPTH && depth % QUEUE_WARN_DEPTH == 0) {
            LOG.warning("WebSocketConnection send queue depth " + depth);
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
                OutboundItem item = sendQueue.take();

                if (item instanceof PoisonPill) {
                    return;
                }

                SendFrame op = (SendFrame) item;

                if (terminated.get() && !(op instanceof SendFrame.CloseFrame)) {
                    continue;
                }

                WebSocket ws = socket;

                if (ws == null || ws.isOutputClosed()) {
                    continue;
                }

                SendContext ctx = new SendContext(ws, sendTimeout, events);

                try {
                    op.execute(ctx);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "send failed: " + op.getClass().getSimpleName(), e);
                    events.onTransportError(this, Causes.rootCause(e));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
                            LOG.warning("WebSocketConnection dead (" + why + "), dropping transport");

                            try {
                                ws.abort();
                            } catch (Exception ignored) {
                            }

                            events.onTransportError(this, new RuntimeException("transport dead: " + why));
                            return;
                        }

                        sendQueue.offer(SendFrame.ping());
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

        private boolean isDropped() {
            return terminated.get();
        }

        private static CompletionStage<?> dropped() {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            // Intentionally no request(n): the JDK default onOpen would grant a
            // read here, before the channel adopts this transport. This
            // override must stay to suppress that. Reads are granted by
            // beginRead() after adoption.
        }

        @Override
        public CompletionStage<?> onText(
                WebSocket webSocket,
                CharSequence data,
                boolean last) {

            if (isDropped()) {
                return dropped();
            }

            textAcc.append(data);
            webSocket.request(1);

            if (last) {
                String full = textAcc.toString();
                textAcc.setLength(0);

                try {
                    channel.onTextMessage(WebSocketConnection.this, full);
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

            if (isDropped()) {
                return dropped();
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

            if (isDropped()) {
                return dropped();
            }

            webSocket.request(1);

            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onPong(
                WebSocket webSocket,
                ByteBuffer message) {

            if (isDropped()) {
                return dropped();
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

            if (isDropped()) {
                return dropped();
            }

            events.onRemoteClose(WebSocketConnection.this, statusCode, reason);

            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            if (isDropped()) {
                return;
            }

            LOG.log(Level.WARNING, "WebSocket error: " + error.getMessage());

            try {
                webSocket.abort();
            } catch (Exception ignored) {
            }

            events.onTransportError(WebSocketConnection.this, error);
        }
    }
}
