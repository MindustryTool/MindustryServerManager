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
    private final Executor handlerExecutor;

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
    private final FrameSink frameSink = this::sendToSession;

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
        this.handlerExecutor = handlerExecutor;
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

    public <Params> void registerSubscriptionHandler(String eventType, Class<Params> paramsClass,
            Function<SubscriptionRequest<Params>, CompletableFuture<Void>> onSubscribe) {
        subscriptions.registerSubscriptionHandler(eventType, paramsClass, onSubscribe);
    }

    public void unregisterSubscriptionHandler(String eventType) {
        subscriptions.unregisterSubscriptionHandler(eventType);
    }

    public boolean hasSubscriptionHandler(String eventType) {
        return subscriptions.hasSubscriptionHandler(eventType);
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
    public boolean abortStream(UUID streamId) {
        return stream.abortStream(streamId);
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

    public CompletableFuture<Void> subscribe(String eventType, Object data,
            Consumer<JsonNode> handler) {
        return subscribe(UUID.randomUUID(), eventType, data, handler, DEFAULT_TIMEOUT);
    }

    public CompletableFuture<Void> subscribe(String eventType, Object data,
            Consumer<JsonNode> handler, Duration timeout) {
        return subscribe(UUID.randomUUID(), eventType, data, handler, timeout);
    }

    public CompletableFuture<Void> subscribe(UUID subscriptionId, String eventType, Object data,
            Consumer<JsonNode> handler) {
        return subscribe(subscriptionId, eventType, data, handler, DEFAULT_TIMEOUT);
    }

    public CompletableFuture<Void> subscribe(UUID subscriptionId, String eventType, Object data,
            Consumer<JsonNode> handler, Duration timeout) {
        return subscriptions.subscribe(subscriptionId, eventType, data, handler, timeout);
    }

    /**
     * If the subscription is already closed, the callback runs immediately.
     */
    public void onSubscriptionClose(UUID subscriptionId, Runnable callback) {
        subscriptions.onSubscriptionClose(subscriptionId, callback);
    }

    public void unsubscribe(UUID requestId) {
        unsubscribe(requestId, null);
    }

    public void unsubscribe(UUID requestId, String reason) {
        subscriptions.unsubscribe(requestId, reason);
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

    public void onTextMessage(String json) {
        runOrdered(() -> dispatchText(json));
    }

    public void onBinaryMessage(ByteBuffer frame) {
        Objects.requireNonNull(frame, "frame");
        runOrdered(() -> stream.handleBinaryMessage(frame));
    }

    private void dispatchText(String json) {
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

        String controlType = message.getType();
        if (controlType != null) {
            if (StreamProtocol.handlesControl(controlType)) {
                stream.handleControl(message);
                return;
            }
            if (SubscriptionProtocol.handlesControl(controlType)) {
                subscriptions.handleControl(message);
                return;
            }
        }

        if (message.getResponseOf() != null) {
            if (subscriptions.tryConsumeResponse(message)) {
                return;
            }
            rpc.settleResponse(message);
            return;
        }

        rpc.dispatchRequest(message);
    }

    private void sendToSession(WsMessage<?> message) {
        sendRaw(getSession(), message);
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
