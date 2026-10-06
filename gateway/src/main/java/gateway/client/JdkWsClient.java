package gateway.client;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import gateway.rpc.WsRpcChannel;
import gateway.session.WsSession;

public class JdkWsClient {

    private static final Logger LOG = Logger.getLogger(JdkWsClient.class.getName());

    public static final Duration PING_INTERVAL = Duration.ofSeconds(20);
    public static final Duration PONG_DEADLINE = Duration.ofSeconds(45);
    public static final Duration RECONNECT_MIN = Duration.ofSeconds(1);
    public static final Duration RECONNECT_MAX = Duration.ofSeconds(10);

    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(10);
    private static final int QUEUE_WARN_DEPTH = 1000;

    private final URI uri;
    private final Supplier<Map<String, String>> headersSupplier;
    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler;
    private final Duration pingInterval;
    private final Duration pongDeadline;

    private final WsRpcChannel rpcChannel;
    private volatile Runnable onOpenCallback;
    private volatile Consumer<Throwable> onCloseCallback;

    private volatile WebSocket webSocket;
    private final WsSession sessionView = new ClientSession();

    private final AtomicBoolean manuallyClosed = new AtomicBoolean(false);
    private final AtomicBoolean connecting = new AtomicBoolean(false);
    private final AtomicInteger reconnectAttempt = new AtomicInteger(0);

    private volatile Instant lastPongAt = Instant.now();
    private volatile ScheduledFuture<?> pingTask;
    private volatile ScheduledFuture<?> reconnectTask;

    private final BlockingQueue<SendOp> sendQueue = new LinkedBlockingQueue<>();
    private volatile Thread senderThread;

    private enum Kind {
        TEXT, BINARY, PING, CLOSE, POISON
    }

    private static final class SendOp {
        final Kind kind;
        final String text;
        final byte[] binary;
        final int closeCode;
        final String closeReason;
        final WebSocket closeTarget;

        private SendOp(Kind kind, String text, byte[] binary, int closeCode, String closeReason,
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

    public static Builder builder(URI uri, WsRpcChannel channel) {
        return new Builder(uri, channel);
    }

    public static final class Builder {
        private final URI uri;
        private final WsRpcChannel channel;
        private Supplier<Map<String, String>> headersSupplier = Map::of;
        private Duration pingInterval = PING_INTERVAL;
        private Duration pongDeadline = PONG_DEADLINE;
        private Executor executor;

        private Builder(URI uri, WsRpcChannel channel) {
            this.uri = Objects.requireNonNull(uri, "uri");
            this.channel = Objects.requireNonNull(channel, "channel");
        }

        public Builder headersSupplier(Supplier<Map<String, String>> supplier) {
            this.headersSupplier = Objects.requireNonNull(supplier, "headersSupplier");
            return this;
        }

        public Builder bearerToken(String token) {
            if (token == null || token.isBlank()) {
                this.headersSupplier = Map::of;
            } else {
                this.headersSupplier = () -> Map.of("Authorization", "Bearer " + token);
            }
            return this;
        }

        public Builder pingInterval(Duration interval) {
            this.pingInterval = Objects.requireNonNull(interval, "pingInterval");
            return this;
        }

        public Builder pongDeadline(Duration deadline) {
            this.pongDeadline = Objects.requireNonNull(deadline, "pongDeadline");
            return this;
        }

        /** Explicit I/O executor for the underlying {@link HttpClient}. */
        public Builder executor(Executor executor) {
            this.executor = executor;
            return this;
        }

        public JdkWsClient build() {
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equalsIgnoreCase("ws") && !scheme.equalsIgnoreCase("wss"))) {
                throw new IllegalArgumentException(
                        "JdkWsClient requires a ws:// or wss:// URI, got: " + uri);
            }
            if (pingInterval.isZero() || pingInterval.isNegative()) {
                throw new IllegalArgumentException("pingInterval must be positive: " + pingInterval);
            }
            if (pongDeadline.isZero() || pongDeadline.isNegative()) {
                throw new IllegalArgumentException("pongDeadline must be positive: " + pongDeadline);
            }
            return new JdkWsClient(this);
        }
    }

    public static JdkWsClient connectTo(URI uri, String bearerToken, WsRpcChannel channel) {
        return builder(uri, channel)
                .bearerToken(bearerToken)
                .build();
    }

    private JdkWsClient(Builder b) {
        this.uri = b.uri;
        this.headersSupplier = b.headersSupplier;
        this.rpcChannel = b.channel;
        this.pingInterval = b.pingInterval;
        this.pongDeadline = b.pongDeadline;
        var httpBuilder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10));
        if (b.executor != null) {
            httpBuilder.executor(b.executor);
        }
        this.httpClient = httpBuilder.build();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "jdk-ws-client");
            t.setDaemon(true);
            return t;
        });
        ensureSenderRunning();
    }

    public JdkWsClient onOpen(Runnable callback) {
        this.onOpenCallback = callback;
        return this;
    }

    public JdkWsClient onClose(Consumer<Throwable> callback) {
        this.onCloseCallback = callback;
        return this;
    }

    public URI getUri() {
        return uri;
    }

    public Duration getPingInterval() {
        return pingInterval;
    }

    public Duration getPongDeadline() {
        return pongDeadline;
    }

    public WsSession session() {
        return sessionView;
    }

    public boolean isOpen() {
        WebSocket ws = this.webSocket;
        return ws != null && !ws.isOutputClosed() && !ws.isInputClosed();
    }

    public int getReconnectAttempt() {
        return reconnectAttempt.get();
    }

    int getSendQueueDepth() {
        return sendQueue.size();
    }

    boolean isSenderAlive() {
        Thread t = senderThread;
        return t != null && t.isAlive();
    }

    boolean isSchedulerShutdown() {
        return scheduler.isShutdown();
    }

    Map<String, String> resolveHeaders() {
        Map<String, String> raw;
        try {
            raw = headersSupplier.get();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "headers supplier failed, dialing without headers", e);
            return Map.of();
        }
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        raw.forEach((k, v) -> {
            if (k != null && !k.isBlank() && v != null && !v.isBlank()) {
                out.put(k, v);
            }
        });
        return out;
    }

    void setTestWebSocket(WebSocket ws) {
        this.webSocket = ws;
        this.lastPongAt = Instant.now();
        rpcChannel.onOpen(sessionView);
        ensureSenderRunning();
    }

    public CompletableFuture<Void> connect() {
        manuallyClosed.set(false);
        return doConnect();
    }

    private CompletableFuture<Void> doConnect() {
        if (!connecting.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(null);
        }
        cancelReconnectTask();
        ensureSenderRunning();

        var builder = httpClient.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10));

        resolveHeaders().forEach(builder::header);

        InnerListener listener = new InnerListener();

        LOG.info("Connecting to WebSocket: " + uri);

        return builder.buildAsync(uri, listener)
                .thenAccept(ws -> {
                    this.webSocket = ws;
                    rpcChannel.onOpen(sessionView);
                    connecting.set(false);
                    reconnectAttempt.set(0);
                    lastPongAt = Instant.now();
                    ws.request(1);
                    startPingTask();
                    LOG.info("WebSocket connected: " + uri);
                    Runnable cb = onOpenCallback;
                    if (cb != null) {
                        try {
                            cb.run();
                        } catch (Exception e) {
                            LOG.log(Level.WARNING, "onOpen callback failed", e);
                        }
                    }
                })
                .exceptionally(err -> {
                    connecting.set(false);
                    LOG.log(Level.INFO, "WebSocket connect failed to " + uri + ": " + err.getMessage());
                    scheduleReconnect();
                    return null;
                });
    }

    public void close() {
        close(1000, "client close");
    }

    public void close(int code, String reason) {
        manuallyClosed.set(true);
        cancelReconnectTask();
        stopPingTask();
        WebSocket ws = this.webSocket;
        this.webSocket = null;
        sendQueue.clear();
        if (ws != null && !ws.isOutputClosed()) {
            sendQueue.offer(SendOp.close(ws, code, reason == null ? "" : reason));
        } else {
            sendQueue.offer(SendOp.poison());
        }
        rpcChannel.onClose(new RuntimeException("JdkWsClient closed: " + reason));
        scheduler.shutdownNow();
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
                if (manuallyClosed.get()) {
                    continue;
                }
                WebSocket ws = webSocket;
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
            handleDrop(rootCause(e));
        }
    }

    private void sendBinaryFrame(WebSocket ws, byte[] bytes) {
        try {
            ws.sendBinary(ByteBuffer.wrap(bytes), true).get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "sendBinary failed", e);
            handleDrop(rootCause(e));
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
        }
    }

    private void sendCloseFrame(SendOp op) {
        WebSocket target = op.closeTarget;
        if (target == null || target.isOutputClosed()) {
            return;
        }
        try {
            target.sendClose(op.closeCode, op.closeReason == null ? "" : op.closeReason)
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
        while (cause instanceof java.util.concurrent.ExecutionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private void enqueue(SendOp op) {
        if (manuallyClosed.get()) {
            throw new IllegalStateException("WebSocket is not open");
        }
        WebSocket ws = webSocket;
        if (ws == null || ws.isOutputClosed()) {
            throw new IllegalStateException("WebSocket is not open");
        }
        sendQueue.offer(op);
        int depth = sendQueue.size();
        if (depth >= QUEUE_WARN_DEPTH && depth % QUEUE_WARN_DEPTH == 0) {
            LOG.warning("JdkWsClient send queue depth " + depth + " for " + uri);
        }
    }

    private synchronized void startPingTask() {
        stopPingTask();
        pingTask = scheduler.scheduleWithFixedDelay(() -> {
            try {
                if (manuallyClosed.get()) {
                    return;
                }
                WebSocket ws = webSocket;
                if (ws == null || ws.isOutputClosed()) {
                    return;
                }
                if (Instant.now().isAfter(lastPongAt.plus(pongDeadline))) {
                    LOG.warning("Pong deadline exceeded, reconnecting: " + uri);
                    try {
                        ws.abort();
                    } catch (Exception ignored) {
                    }
                    handleDrop(new RuntimeException("pong timeout"));
                    return;
                }
                sendQueue.offer(SendOp.ping());
            } catch (Exception e) {
                LOG.log(Level.FINE, "Ping task error", e);
            }
        }, pingInterval.toMillis(), pingInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    private synchronized void stopPingTask() {
        if (pingTask != null) {
            pingTask.cancel(false);
            pingTask = null;
        }
    }

    private synchronized void cancelReconnectTask() {
        if (reconnectTask != null) {
            reconnectTask.cancel(false);
            reconnectTask = null;
        }
    }

    private void handleDrop(Throwable cause) {
        this.webSocket = null;
        stopPingTask();
        int dropped = sendQueue.size();
        if (dropped > 0) {
            sendQueue.clear();
            LOG.fine("Dropped " + dropped + " queued sends after connection drop: " + uri);
        }
        rpcChannel.onClose(cause);
        Consumer<Throwable> cb = onCloseCallback;
        if (cb != null) {
            try {
                cb.accept(cause);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "onClose callback failed", e);
            }
        }
        if (!manuallyClosed.get()) {
            scheduleReconnect();
        }
    }

    private synchronized void scheduleReconnect() {
        if (manuallyClosed.get() || reconnectTask != null) {
            return;
        }
        int attempt = reconnectAttempt.getAndIncrement();
        long exp = RECONNECT_MIN.toMillis() << Math.min(attempt, 5);
        long capped = Math.min(exp, RECONNECT_MAX.toMillis());
        long jitter = ThreadLocalRandom.current().nextLong(0, 500);
        long delay = Math.min(capped + jitter, RECONNECT_MAX.toMillis() + 500);
        LOG.info("Scheduling WebSocket reconnect in " + delay + "ms (attempt " + (attempt + 1) + ")");
        reconnectTask = scheduler.schedule(() -> {
            reconnectTask = null;
            if (!manuallyClosed.get()) {
                doConnect();
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    static long backoffDelayMillis(int attempt) {
        long exp = RECONNECT_MIN.toMillis() << Math.min(attempt, 5);
        return Math.min(exp, RECONNECT_MAX.toMillis());
    }

    private class InnerListener implements WebSocket.Listener {
        private final StringBuilder textAcc = new StringBuilder();
        private ByteArrayOutputStream binAcc;

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            textAcc.append(data);
            webSocket.request(1);
            if (last) {
                String full = textAcc.toString();
                textAcc.setLength(0);
                try {
                    rpcChannel.onTextMessage(full);
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "RPC dispatch failed", e);
                }
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
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
                    dispatchBinary(ByteBuffer.wrap(done.toByteArray()));
                }
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
            lastPongAt = Instant.now();
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            LOG.info("WebSocket closed: " + statusCode + " " + reason);
            handleDrop(new RuntimeException("remote close " + statusCode + ": " + reason));
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            LOG.log(Level.WARNING, "WebSocket error: " + error.getMessage(), error);
            try {
                webSocket.abort();
            } catch (Exception ignored) {
            }
            handleDrop(error);
        }
    }

    void dispatchBinary(ByteBuffer data) {
        try {
            rpcChannel.onBinaryMessage(data);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Stream ingest failed", e);
        }
    }

    private class ClientSession implements WsSession {
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
            JdkWsClient.this.close(code, reason);
        }

        @Override
        public boolean isOpen() {
            return JdkWsClient.this.isOpen();
        }
    }
}
