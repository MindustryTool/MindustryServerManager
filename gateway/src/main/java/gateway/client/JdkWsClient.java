package gateway.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import gateway.rpc.WsRpcChannel;

/**
 * Connection policy for one logical socket: dial, backoff, kick, and
 * terminal close. The live socket and everything bound to it lives in
 * the current {@link Transport}; session truth lives in the channel.
 * All policy transitions run through one synchronized path.
 */
public class JdkWsClient {

    private static final Logger LOG = Logger.getLogger(JdkWsClient.class.getName());

    public static final Duration PING_INTERVAL = Duration.ofSeconds(20);
    public static final Duration PONG_DEADLINE = Duration.ofSeconds(45);
    public static final Duration RECONNECT_MIN = Duration.ofSeconds(1);
    public static final Duration RECONNECT_MAX = Duration.ofSeconds(60);

    enum State {
        IDLE,
        CONNECTING,
        OPEN,
        RECONNECT_WAIT,
        KICKED,
        CLOSED
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
        private WsDialer dialer;

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
                this.headersSupplier = () -> Map.of(
                        "Authorization",
                        "Bearer " + token);
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

        public Builder executor(Executor executor) {
            this.executor = executor;
            return this;
        }

        public Builder dialer(WsDialer dialer) {
            this.dialer = Objects.requireNonNull(dialer, "dialer");
            return this;
        }

        public JdkWsClient build() {
            String scheme = uri.getScheme();

            if (scheme == null
                    || (!scheme.equalsIgnoreCase("ws")
                    && !scheme.equalsIgnoreCase("wss"))) {

                throw new IllegalArgumentException(
                        "JdkWsClient requires a ws:// or wss:// URI, got: " + uri);
            }

            if (pingInterval.isZero() || pingInterval.isNegative()) {
                throw new IllegalArgumentException(
                        "pingInterval must be positive: " + pingInterval);
            }

            if (pongDeadline.isZero() || pongDeadline.isNegative()) {
                throw new IllegalArgumentException(
                        "pongDeadline must be positive: " + pongDeadline);
            }

            return new JdkWsClient(this);
        }
    }

    private final URI uri;
    private final Supplier<Map<String, String>> headersSupplier;
    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler;
    private final Duration pingInterval;
    private final Duration pongDeadline;
    private final WsDialer dialer;

    private final WsRpcChannel rpcChannel;
    private volatile Runnable onOpenCallback;
    private volatile Consumer<Throwable> onCloseCallback;

    private volatile State state = State.IDLE;
    private volatile Transport current;
    private volatile Transport dialling;
    private volatile ScheduledFuture<?> reconnectTask;
    private final AtomicInteger reconnectAttempt = new AtomicInteger(0);

    private final Transport.Events transportEvents = new Transport.Events() {
        @Override
        public void onRemoteClose(Transport transport, int statusCode, String reason) {
            if (statusCode == WsRpcChannel.REPLACED_CLOSE_CODE) {
                onKick(transport, statusCode, reason);
                return;
            }

            LOG.info("WebSocket closed: " + statusCode + " " + reason);
            onDrop(transport, new RuntimeException("remote close " + statusCode + ": " + reason));
        }

        @Override
        public void onTransportError(Transport transport, Throwable error) {
            onDrop(transport, error);
        }
    };

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

        if (b.dialer != null) {
            this.dialer = b.dialer;
        } else {
            this.dialer = listener -> {
                var webSocketBuilder = httpClient.newWebSocketBuilder()
                        .connectTimeout(Duration.ofSeconds(10));
                resolveHeaders().forEach(webSocketBuilder::header);
                return webSocketBuilder.buildAsync(uri, listener);
            };
        }

        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "jdk-ws-client");
            t.setDaemon(true);
            return t;
        });
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

    public boolean isOpen() {
        return state == State.OPEN;
    }

    public int getReconnectAttempt() {
        return reconnectAttempt.get();
    }

    State getState() {
        return state;
    }

    int getTransportQueueDepth() {
        Transport t = current;
        return t == null ? 0 : t.queueDepth();
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

    /** Dial now from IDLE, RECONNECT_WAIT, or KICKED. Everywhere else is a no-op. */
    public void connect() {
        Transport next = beginDial();

        if (next != null) {
            dial(next);
        }
    }

    public void close() {
        close(1000, "client close");
    }

    /** Terminal close from any state. A closed client never redials. */
    public void close(int code, String reason) {
        Transport prev;

        synchronized (this) {
            if (state == State.CLOSED) {
                return;
            }

            prev = current;
            current = null;
            dialling = null;
            state = State.CLOSED;
            cancelReconnectTask();
        }

        if (prev != null) {
            prev.shutdownGracefully(code, reason);
        }

        rpcChannel.onClose(new RuntimeException("JdkWsClient closed: " + reason));

        scheduler.shutdownNow();
    }

    private synchronized Transport beginDial() {
        switch (state) {
            case IDLE, RECONNECT_WAIT, KICKED -> {
                cancelReconnectTask();

                Transport next = new Transport(
                        scheduler, pingInterval, pongDeadline, rpcChannel, transportEvents);
                dialling = next;
                state = State.CONNECTING;
                return next;
            }
            default -> {
                return null;
            }
        }
    }

    private void dial(Transport transport) {
        dialer.dial(transport.listener())
                .thenAccept(ws -> onDialSuccess(transport, ws))
                .exceptionally(err -> {
                    onDialFailure(transport, err);
                    return null;
                });
    }

    private void onDialSuccess(Transport transport, WebSocket socket) {
        synchronized (this) {
            if (state != State.CONNECTING || dialling != transport) {
                try {
                    socket.abort();
                } catch (Exception ignored) {
                }

                return;
            }

            transport.attach(socket);
            current = transport;
            dialling = null;
            state = State.OPEN;
            reconnectAttempt.set(0);
        }

        rpcChannel.onOpen(transport);

        Runnable cb = onOpenCallback;

        if (cb != null) {
            try {
                cb.run();
            } catch (Exception e) {
                LOG.log(Level.WARNING, "onOpen callback failed", e);
            }
        }
    }

    private void onDialFailure(Transport transport, Throwable err) {
        synchronized (this) {
            if (state != State.CONNECTING || dialling != transport) {
                return;
            }

            dialling = null;
            state = State.RECONNECT_WAIT;
        }

        Throwable cause = rootCause(err);
        LOG.log(Level.INFO, "WebSocket connect failed to " + uri + ": " + cause.getMessage());

        scheduleReconnect();
    }

    private void onDrop(Transport transport, Throwable cause) {
        synchronized (this) {
            if (transport != current) {
                return;
            }

            current = null;

            if (state == State.CLOSED) {
                return;
            }

            state = State.RECONNECT_WAIT;
        }

        transport.terminate();
        rpcChannel.onClose(transport, cause);
        fireOnClose(cause);
        scheduleReconnect();
    }

    private void onKick(Transport transport, int statusCode, String reason) {
        synchronized (this) {
            if (transport != current) {
                return;
            }

            current = null;

            if (state == State.CLOSED) {
                return;
            }

            state = State.KICKED;
        }

        RuntimeException kick = new RuntimeException(
                "Kicked by server (close " + statusCode + "): " + reason);

        transport.terminate();
        rpcChannel.onClose(transport, kick);

        LOG.warning("WebSocket kicked by server, auto-reconnect disabled: "
                + uri + " code=" + statusCode);

        fireOnClose(kick);
    }

    private void fireOnClose(Throwable cause) {
        Consumer<Throwable> cb = onCloseCallback;

        if (cb != null) {
            try {
                cb.accept(cause);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "onClose callback failed", e);
            }
        }
    }

    private synchronized void scheduleReconnect() {
        if (state != State.RECONNECT_WAIT
                || scheduler.isShutdown()
                || reconnectTask != null) {

            return;
        }

        int attempt = reconnectAttempt.getAndIncrement();

        long exp = RECONNECT_MIN.toMillis() << Math.min(attempt, 5);
        long capped = Math.min(exp, RECONNECT_MAX.toMillis());
        long jitter = ThreadLocalRandom.current().nextLong(0, 500);
        long delay = Math.min(capped + jitter, RECONNECT_MAX.toMillis() + 500);

        LOG.info("Scheduling WebSocket reconnect in " + delay + "ms (attempt " + (attempt + 1) + ")");

        reconnectTask = scheduler.schedule(
                () -> {
                    Transport next;

                    synchronized (JdkWsClient.this) {
                        reconnectTask = null;

                        if (state != State.RECONNECT_WAIT || scheduler.isShutdown()) {
                            return;
                        }

                        next = beginDial();
                    }

                    if (next != null) {
                        dial(next);
                    }
                },
                delay,
                TimeUnit.MILLISECONDS);
    }

    private synchronized void cancelReconnectTask() {
        if (reconnectTask != null) {
            reconnectTask.cancel(false);
            reconnectTask = null;
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

    static long backoffDelayMillis(int attempt) {
        long exp = RECONNECT_MIN.toMillis() << Math.min(attempt, 5);
        return Math.min(exp, RECONNECT_MAX.toMillis());
    }
}
