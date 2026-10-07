package gateway.rpc;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import gateway.WsMessage;
import gateway.session.WsSession;

public class WsRpcChannel implements SessionGate {

    private static final Logger LOG = Logger.getLogger(WsRpcChannel.class.getName());
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration SESSION_WAIT = Duration.ofMinutes(5);

    private static final TypeReference<WsMessage<JsonNode>> MESSAGE_TYPE = new TypeReference<WsMessage<JsonNode>>() {
    };

    private final ObjectMapper mapper;
    private final ScheduledExecutorService scheduler;

    private volatile WsSession current;
    private final Set<CompletableFuture<WsSession>> sessionWaiters =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    private volatile boolean shutdown = false;

    /**
     * Per-channel ordered ingress: inbound text and binary frames are processed
     * one at a time, in call order. Guards stream reassembly against an adapter
     * that delivers frames from more than one thread (a {@code stream-start}
     * must never be overtaken by its own chunks).
     */
    private final ArrayDeque<Runnable> ingressQueue = new ArrayDeque<>();
    private boolean ingressActive = false;

    private final RpcProtocol rpc;
    private final StreamProtocol stream;
    private final SubscriptionProtocol subscriptions;
    private final FrameSink frameSink = this::sendRaw;

    public static WsRpcChannel create() {
        return new WsRpcChannel(defaultMapper(), defaultScheduler(), Runnable::run);
    }

    public static WsRpcChannel withExecutor(Executor handlerExecutor) {
        Objects.requireNonNull(handlerExecutor, "handlerExecutor");
        return new WsRpcChannel(defaultMapper(), defaultScheduler(), handlerExecutor);
    }

    public static WsRpcChannel withMapper(ObjectMapper mapper, Executor handlerExecutor) {
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(handlerExecutor, "handlerExecutor");
        return new WsRpcChannel(mapper, defaultScheduler(), handlerExecutor);
    }

    private WsRpcChannel(ObjectMapper mapper, ScheduledExecutorService scheduler,
            Executor handlerExecutor) {
        this.mapper = mapper;
        this.scheduler = scheduler;
        this.rpc = new RpcProtocol(mapper, scheduler, handlerExecutor, DEFAULT_TIMEOUT, frameSink, this);
        this.stream = new StreamProtocol(mapper, scheduler, handlerExecutor, DEFAULT_TIMEOUT, frameSink, this,
                rpc, rpc);
        this.subscriptions = new SubscriptionProtocol(mapper, scheduler, DEFAULT_TIMEOUT, frameSink, this);
    }

    public static ObjectMapper defaultMapper() {
        return new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true)
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
                .findAndRegisterModules();
    }

    private static ScheduledExecutorService defaultScheduler() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ws-rpc-timeout");
            t.setDaemon(true);
            return t;
        });
    }

    public void onOpen(WsSession session) {
        WsSession toClose = null;
        synchronized (this) {
            Objects.requireNonNull(session, "session");
            if (shutdown) {
                throw new IllegalStateException("Channel is shut down");
            }
            if (!session.isOpen()) {
                throw new IllegalStateException("Cannot signal open for a closed session");
            }

            WsSession prev = current;
            if (prev != null && sessionsEqual(prev, session)) {
                return;
            }
            current = session;
            for (CompletableFuture<WsSession> waiter : sessionWaiters) {
                waiter.complete(session);
            }
            sessionWaiters.clear();
            if (prev != null) {
                toClose = prev;
            }
        }
        if (toClose != null) {
            try {
                toClose.close(WsProtocol.REPLACED_CLOSE_CODE, "Replaced by new connection");
            } catch (Exception e) {
                LOG.log(Level.FINE, "Failed to close replaced session", e);
            }
        }
    }

    private static boolean sessionsEqual(WsSession a, WsSession b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        try {
            return a.equals(b);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Wait for the current open session, parking on the gate up to SESSION_WAIT.
     * Never wedges: a close fails waiters, the next open wakes them.
     */
    @Override
    public CompletableFuture<WsSession> awaitOpen() {
        return awaitOpenUnbounded().orTimeout(SESSION_WAIT.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public WsSession current() {
        return current;
    }

    /**
     * Wait for an open session up to the given timeout.
     *
     * @return future with the open session, or TimeoutException when none appears
     */
    public CompletableFuture<WsSession> awaitSession(Duration timeout) {
        Duration wait = timeout != null ? timeout : SESSION_WAIT;
        return awaitOpenUnbounded().orTimeout(wait.toMillis(), TimeUnit.MILLISECONDS);
    }

    private CompletableFuture<WsSession> awaitOpenUnbounded() {
        WsSession s = current;
        if (s != null && s.isOpen()) {
            return CompletableFuture.completedFuture(s);
        }
        synchronized (this) {
            s = current;
            if (s != null && s.isOpen()) {
                return CompletableFuture.completedFuture(s);
            }
            if (shutdown) {
                CompletableFuture<WsSession> failed = new CompletableFuture<>();
                failed.completeExceptionally(new IllegalStateException("WsRpcChannel is shut down"));
                return failed;
            }
            CompletableFuture<WsSession> waiter = new CompletableFuture<>();
            sessionWaiters.add(waiter);
            waiter.whenComplete((v, e) -> sessionWaiters.remove(waiter));
            return waiter;
        }
    }

    public void onClose() {
        onClose(new RuntimeException("WebSocket connection closed"));
    }

    public WsSession getSession() {
        return current();
    }

    public ObjectMapper getObjectMapper() {
        return mapper;
    }

    public <Req, Res> void registerHandler(String type, Class<Req> requestClass, Function<Req, Res> handler) {
        rpc.registerHandler(type, requestClass, handler);
    }

    public void unregisterHandler(String type) {
        rpc.unregisterHandler(type);
    }

    public boolean hasHandler(String type) {
        return rpc.hasHandler(type);
    }

    public int pendingCount() {
        return rpc.pendingCount();
    }

    public <Meta, Res> void registerStreamHandler(String type, Class<Meta> metaClass, Class<Res> responseType,
            BiFunction<Meta, byte[], Res> handler) {
        stream.registerStreamHandler(type, metaClass, responseType, handler);
    }

    public void unregisterStreamHandler(String type) {
        stream.unregisterStreamHandler(type);
    }

    public boolean hasStreamHandler(String type) {
        return stream.hasStreamHandler(type);
    }

    public <Params> void registerEventListener(String event, Class<Params> paramsClass,
            Function<SubscriptionRequest<Params>, CompletableFuture<Void>> onListen) {
        subscriptions.registerEventListener(event, paramsClass, onListen);
    }

    public void unregisterEventListener(String event) {
        subscriptions.unregisterEventListener(event);
    }

    public boolean hasEventListener(String event) {
        return subscriptions.hasEventListener(event);
    }

    public int pendingStreamCount() {
        return stream.pendingStreamCount();
    }

    /**
     * Discard a stream slot and notify the peer so it discards its side too.
     *
     * <p>
     * The abort notification is fire-and-forget: no reply is expected or
     * sent for it. When no session is open only local state is discarded.
     *
     * @return true when a sender or receiver slot was discarded
     */
    public boolean abortStream(UUID streamId, String type) {
        return stream.abortStream(streamId, type);
    }

    public CompletableFuture<Void> sendRequest(String type, Object payload) {
        return sendRequest(type, payload, Void.class, DEFAULT_TIMEOUT);
    }

    public <Res> CompletableFuture<Res> sendRequest(String type, Object payload, Class<Res> responseType) {
        return sendRequest(type, payload, responseType, DEFAULT_TIMEOUT);
    }

    public <Res> CompletableFuture<Res> sendRequest(String type, Object payload, Class<Res> responseType,
            Duration timeout) {
        return rpc.sendRequest(type, payload, responseType, timeout);
    }

    public void sendNotification(String type, Object payload) {
        rpc.sendNotification(type, payload);
    }

    public CompletableFuture<Void> listen(String event, Object data,
            Consumer<JsonNode> handler) {
        return listen(UUID.randomUUID(), event, data, handler, DEFAULT_TIMEOUT);
    }

    public CompletableFuture<Void> listen(String event, Object data,
            Consumer<JsonNode> handler, Duration timeout) {
        return listen(UUID.randomUUID(), event, data, handler, timeout);
    }

    public CompletableFuture<Void> listen(UUID listenId, String event, Object data,
            Consumer<JsonNode> handler) {
        return listen(listenId, event, data, handler, DEFAULT_TIMEOUT);
    }

    public CompletableFuture<Void> listen(UUID listenId, String event, Object data,
            Consumer<JsonNode> handler, Duration timeout) {
        return subscriptions.listen(listenId, event, data, handler, timeout);
    }

    /**
     * If the event stream is already closed, the callback runs immediately.
     */
    public void onListenClose(UUID listenId, Runnable callback) {
        subscriptions.onListenClose(listenId, callback);
    }

    public void unlisten(UUID listenId) {
        unlisten(listenId, null);
    }

    public void unlisten(UUID listenId, String reason) {
        subscriptions.unlisten(listenId, reason);
    }

    public CompletableFuture<Void> sendStream(String type, Object metadata, ByteBuffer data) {
        return sendStream(type, metadata, data, Void.class);
    }

    public <Res> CompletableFuture<Res> sendStream(String type, Object metadata, ByteBuffer data,
            Class<Res> responseType) {
        return sendStream(type, metadata, data, responseType, DEFAULT_TIMEOUT);
    }

    public <Res> CompletableFuture<Res> sendStream(String type, Object metadata, ByteBuffer data,
            Class<Res> responseType, Duration timeout) {
        return stream.sendStream(type, metadata, data, responseType, timeout);
    }

    public CompletableFuture<Void> sendStream(String type, Object metadata, byte[] data) {
        return sendStream(type, metadata, data, Void.class);
    }

    public <Res> CompletableFuture<Res> sendStream(String type, Object metadata, byte[] data,
            Class<Res> responseType) {
        return sendStream(type, metadata, data, responseType, DEFAULT_TIMEOUT);
    }

    public <Res> CompletableFuture<Res> sendStream(String type, Object metadata, byte[] data,
            Class<Res> responseType, Duration timeout) {
        return stream.sendStream(type, metadata, data, responseType, timeout);
    }

    public CompletableFuture<Void> sendStream(String type, Object metadata, InputStream data) {
        return sendStream(type, metadata, data, Void.class);
    }

    public <Res> CompletableFuture<Res> sendStream(String type, Object metadata, InputStream data,
            Class<Res> responseType) {
        return sendStream(type, metadata, data, responseType, DEFAULT_TIMEOUT);
    }

    public <Res> CompletableFuture<Res> sendStream(String type, Object metadata, InputStream data,
            Class<Res> responseType, Duration timeout) {
        return stream.sendStream(type, metadata, data, responseType, timeout);
    }

    /**
     * Run an inbound frame's processing in per-channel arrival order.
     *
     * <p>
     * When the lane is free the caller executes the task inline, so a
     * single-threaded adapter keeps synchronous semantics. When the lane is
     * busy the task is queued and the active runner drains it FIFO. Either way
     * no two frames are processed concurrently on this channel.
     */
    private void runOrdered(Runnable task) {
        synchronized (ingressQueue) {
            if (ingressActive) {
                ingressQueue.addLast(task);
                return;
            }
            ingressActive = true;
        }

        Runnable current = task;
        while (true) {
            try {
                current.run();
            } catch (Throwable e) {
                LOG.log(Level.WARNING, "Ingress frame processing failed", e);
            }
            synchronized (ingressQueue) {
                current = ingressQueue.pollFirst();
                if (current == null) {
                    ingressActive = false;
                    return;
                }
            }
        }
    }

    public void onTextMessage(WsSession from, String json) {
        Objects.requireNonNull(from, "from");
        runOrdered(() -> dispatchText(from, json));
    }

    public void onBinaryMessage(ByteBuffer frame) {
        Objects.requireNonNull(frame, "frame");
        runOrdered(() -> stream.handleBinaryMessage(frame));
    }

    private void dispatchText(WsSession from, String json) {
        if (json == null || json.isBlank()) {
            return;
        }
        final WsMessage<JsonNode> message;
        try {
            message = mapper.readValue(json, MESSAGE_TYPE);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Dropping unparsable RPC frame: " + e.getMessage(), e);
            return;
        }
        if (message == null) {
            return;
        }

        String kind = message.getKind();
        if (kind == null) {
            LOG.info("Dropping RPC frame without kind");
            return;
        }
        FrameContext<WsMessage<JsonNode>> ctx = new FrameContext<>(message, from);
        switch (kind) {
            case WsProtocol.REQUEST_TYPE:
                if (!requireType(message)) {
                    return;
                }
                rpc.dispatchRequest(ctx);
                return;
            case WsProtocol.NOTIFICATION_TYPE:
                if (!requireType(message)) {
                    return;
                }
                rpc.dispatchNotification(ctx);
                return;
            case WsProtocol.RESPONSE_TYPE:
            case WsProtocol.RESPONSE_ERROR_TYPE:
                if (!requireType(message)) {
                    return;
                }
                rpc.settleResponse(ctx);
                return;
            case WsProtocol.STREAM_START_TYPE:
            case WsProtocol.STREAM_DONE_TYPE:
            case WsProtocol.STREAM_ABORT_TYPE:
            case WsProtocol.STREAM_REPLY_START_TYPE:
            case WsProtocol.STREAM_REPLY_DONE_TYPE:
                if (!requireType(message)) {
                    return;
                }
                stream.handleControl(ctx);
                return;
            case WsProtocol.LISTEN_TYPE:
            case WsProtocol.UNLISTEN_TYPE:
                if (!requireEvent(message)) {
                    return;
                }
                subscriptions.handleControl(ctx);
                return;
            case WsProtocol.LISTENING_TYPE:
            case WsProtocol.EVENT_TYPE:
            case WsProtocol.LISTEN_ENDED_TYPE:
            case WsProtocol.LISTEN_ERROR_TYPE:
                if (!requireEvent(message)) {
                    return;
                }
                subscriptions.handleClientFrame(ctx);
                return;
            default:
                LOG.info("Dropping RPC frame with unknown kind: " + kind);
        }
    }

    private static boolean requireType(WsMessage<JsonNode> message) {
        if (message.getType() == null || message.getEvent() != null) {
            LOG.info("Dropping " + message.getKind() + " frame with missing or misplaced subject");
            return false;
        }
        return true;
    }

    private static boolean requireEvent(WsMessage<JsonNode> message) {
        if (message.getEvent() == null || message.getType() != null) {
            LOG.info("Dropping " + message.getKind() + " frame with missing or misplaced subject");
            return false;
        }
        return true;
    }

    private void sendRaw(WsSession s, WsMessage<?> message) {
        if (s == null || !s.isOpen()) {
            LOG.info("Dropping RPC response, no open session for type=" + message.getType());
            return;
        }
        try {
            s.sendText(mapper.writeValueAsString(message));
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to send RPC frame", e);
        }
    }

    /**
     * Close for a known session. Stale closes for a superseded session are ignored.
     *
     * @return true when this close cleared the current session
     */
    public synchronized boolean onClose(WsSession session, Throwable cause) {
        if (session != null && current != null && !sessionsEqual(session, current)) {
            return false;
        }
        failAll(causeOf(cause));
        return true;
    }

    public synchronized void onClose(Throwable cause) {
        failAll(causeOf(cause));
    }

    private static RuntimeException causeOf(Throwable cause) {
        if (cause instanceof RuntimeException re) {
            return re;
        }
        return new RuntimeException(cause);
    }

    private void failAll(RuntimeException err) {
        synchronized (ingressQueue) {
            ingressQueue.clear();
        }
        for (CompletableFuture<WsSession> waiter : sessionWaiters) {
            waiter.completeExceptionally(err);
        }
        sessionWaiters.clear();
        current = null;
        stream.teardown();
        subscriptions.teardownClient(err);
        subscriptions.teardownServer();
        rpc.teardownPending(err);
    }

    public void shutdown() {
        synchronized (this) {
            shutdown = true;
        }
        onClose(new RuntimeException("WsRpcChannel shutdown"));
        scheduler.shutdownNow();
    }
}
