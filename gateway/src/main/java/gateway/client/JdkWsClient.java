package gateway.client;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import gateway.rpc.WsRpcChannel;
import gateway.session.WsSession;

/**
 * Outbound WebSocket client on Java 17 {@link HttpClient#newWebSocketBuilder()}.
 *
 * <ul>
 * <li>Optional {@code Authorization: Bearer <token>} handshake header plus caller headers.</li>
 * <li>Forwards text frames into {@link WsRpcChannel#onTextMessage} and binary frames
 * into an optional binary handler.</li>
 * <li>20s ping keepalives, 45s pong-deadline detection, exponential backoff
 * reconnect (1s to 30s + jitter).</li>
 * </ul>
 */
public class JdkWsClient {

    private static final Logger LOG = Logger.getLogger(JdkWsClient.class.getName());

    public static final Duration PING_INTERVAL = Duration.ofSeconds(20);
    public static final Duration PONG_DEADLINE = Duration.ofSeconds(45);
    public static final Duration RECONNECT_MIN = Duration.ofSeconds(1);
    public static final Duration RECONNECT_MAX = Duration.ofSeconds(30);

    private final URI uri;
    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;

    private final Map<String, String> headers = new ConcurrentHashMap<>();
    private volatile String bearerToken;

    private volatile WsRpcChannel rpcChannel;
    private volatile Consumer<ByteBuffer> binaryHandler;
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

    public JdkWsClient(URI uri) {
        this(uri, null, null);
    }

    public JdkWsClient(URI uri, String bearerToken) {
        this(uri, bearerToken, null);
    }

    public JdkWsClient(URI uri, String bearerToken, ScheduledExecutorService scheduler) {
        this.uri = Objects.requireNonNull(uri, "uri");
        this.bearerToken = bearerToken;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        if (scheduler != null) {
            this.scheduler = scheduler;
            this.ownsScheduler = false;
        } else {
            this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "jdk-ws-client");
                t.setDaemon(true);
                return t;
            });
            this.ownsScheduler = true;
        }
    }

    // ------------------------------------------------------------------
    // Configuration
    // ------------------------------------------------------------------

    public JdkWsClient setBearerToken(String token) {
        this.bearerToken = token;
        return this;
    }

    public JdkWsClient putHeader(String name, String value) {
        headers.put(name, value);
        return this;
    }

    public JdkWsClient setHeaders(Map<String, String> map) {
        if (map != null) {
            headers.putAll(map);
        }
        return this;
    }

    public JdkWsClient setRpcChannel(WsRpcChannel channel) {
        this.rpcChannel = channel;
        if (channel != null) {
            channel.setSession(sessionView);
        }
        return this;
    }

    public JdkWsClient setBinaryHandler(Consumer<ByteBuffer> handler) {
        this.binaryHandler = handler;
        return this;
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

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /**
     * Initiate (or re-initiate) the connection. Safe to call repeatedly; only
     * one concurrent handshake runs at a time.
     */
    public CompletableFuture<Void> connect() {
        manuallyClosed.set(false);
        return doConnect();
    }

    private CompletableFuture<Void> doConnect() {
        if (!connecting.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(null);
        }
        cancelReconnectTask();

        var builder = httpClient.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10));
        if (bearerToken != null && !bearerToken.isBlank()) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        headers.forEach(builder::header);

        InnerListener listener = new InnerListener();
        return builder.buildAsync(uri, listener)
                .thenAccept(ws -> {
                    this.webSocket = ws;
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
                    LOG.log(Level.WARNING, "WebSocket connect failed to " + uri + ": " + err.getMessage(), err);
                    scheduleReconnect();
                    return null;
                });
    }

    /** Stop pings, cancel reconnects, close the socket and fail pending RPC. */
    public void close() {
        close(1000, "client close");
    }

    public void close(int code, String reason) {
        manuallyClosed.set(true);
        cancelReconnectTask();
        stopPingTask();
        WebSocket ws = this.webSocket;
        this.webSocket = null;
        if (ws != null && !ws.isOutputClosed()) {
            try {
                ws.sendClose(code, reason == null ? "" : reason);
            } catch (Exception e) {
                try {
                    ws.abort();
                } catch (Exception ignored) {
                }
            }
        }
        WsRpcChannel ch = rpcChannel;
        if (ch != null) {
            ch.onClose(new RuntimeException("JdkWsClient closed: " + reason));
        }
        if (ownsScheduler) {
            scheduler.shutdownNow();
        }
    }

    // ------------------------------------------------------------------
    // Keepalive + reconnect
    // ------------------------------------------------------------------

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
                if (Instant.now().isAfter(lastPongAt.plus(PONG_DEADLINE))) {
                    LOG.warning("Pong deadline exceeded, reconnecting: " + uri);
                    try {
                        ws.abort();
                    } catch (Exception ignored) {
                    }
                    handleDrop(new RuntimeException("pong timeout"));
                    return;
                }
                ByteBuffer ping = ByteBuffer.wrap(new byte[8]);
                ThreadLocalRandom.current().nextBytes(ping.array());
                ws.sendPing(ping).exceptionally(err -> {
                    LOG.log(Level.FINE, "Ping failed", err);
                    return null;
                });
            } catch (Exception e) {
                LOG.log(Level.FINE, "Ping task error", e);
            }
        }, PING_INTERVAL.toMillis(), PING_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
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
        WsRpcChannel ch = rpcChannel;
        if (ch != null) {
            ch.onClose(cause);
        }
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

    /** Visible for tests: backoff delay for a 0-based attempt index. */
    static long backoffDelayMillis(int attempt) {
        long exp = RECONNECT_MIN.toMillis() << Math.min(attempt, 5);
        return Math.min(exp, RECONNECT_MAX.toMillis());
    }

    // ------------------------------------------------------------------
    // Listener
    // ------------------------------------------------------------------

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
                WsRpcChannel ch = rpcChannel;
                if (ch != null) {
                    try {
                        ch.onTextMessage(full);
                    } catch (Exception e) {
                        LOG.log(Level.WARNING, "RPC dispatch failed", e);
                    }
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
                Consumer<ByteBuffer> handler = binaryHandler;
                if (handler != null && done != null) {
                    try {
                        handler.accept(ByteBuffer.wrap(done.toByteArray()));
                    } catch (Exception e) {
                        LOG.log(Level.WARNING, "Binary handler failed", e);
                    }
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

    // ------------------------------------------------------------------
    // WsSession view
    // ------------------------------------------------------------------

    private class ClientSession implements WsSession {
        @Override
        public void sendText(String text) {
            WebSocket ws = webSocket;
            if (ws == null || ws.isOutputClosed()) {
                throw new IllegalStateException("WebSocket is not open");
            }
            ws.sendText(text, true).exceptionally(err -> {
                LOG.log(Level.WARNING, "sendText failed", err);
                return null;
            });
        }

        @Override
        public void sendBinary(ByteBuffer data) {
            WebSocket ws = webSocket;
            if (ws == null || ws.isOutputClosed()) {
                throw new IllegalStateException("WebSocket is not open");
            }
            ByteBuffer copy = data.duplicate();
            ws.sendBinary(copy, true).exceptionally(err -> {
                LOG.log(Level.WARNING, "sendBinary failed", err);
                return null;
            });
        }

        @Override
        public void close(int code, String reason) {
            WebSocket ws = webSocket;
            if (ws != null) {
                try {
                    ws.sendClose(code, reason == null ? "" : reason);
                } catch (Exception e) {
                    LOG.log(Level.FINE, "close failed", e);
                }
            }
        }

        @Override
        public boolean isOpen() {
            return JdkWsClient.this.isOpen();
        }
    }
}
