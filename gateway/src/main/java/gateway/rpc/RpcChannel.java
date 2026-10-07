package gateway.rpc;


import java.io.InputStream;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import gateway.session.SessionProvider;
import gateway.session.WsSession;
import gateway.stream.StreamProtocol;
import gateway.subscription.SubscriptionProtocol;
import gateway.subscription.SubscriptionRequest;
import gateway.wire.WsMessage;
import gateway.wire.WsProtocol;

public class RpcChannel implements SessionProvider {

    private static final Logger LOG = Logger.getLogger(RpcChannel.class.getName());
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration SESSION_POLL_INTERVAL = Duration.ofMillis(50);

    private static final TypeReference<WsMessage<JsonNode>> MESSAGE_TYPE = new TypeReference<WsMessage<JsonNode>>() {
    };

    private final ObjectMapper mapper;
    private final ScheduledExecutorService scheduler;

    private volatile WsSession current;
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
    private final FrameWriter frameSink = this::sendRaw;

    public static RpcChannel create() {
        return new RpcChannel(defaultMapper(), defaultScheduler(), Runnable::run);
    }

    public static RpcChannel withExecutor(Executor handlerExecutor) {
        Objects.requireNonNull(handlerExecutor, "handlerExecutor");
        return new RpcChannel(defaultMapper(), defaultScheduler(), handlerExecutor);
    }

    public static RpcChannel withMapper(ObjectMapper mapper, Executor handlerExecutor) {
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(handlerExecutor, "handlerExecutor");
        return new RpcChannel(mapper, defaultScheduler(), handlerExecutor);
    }

    private RpcChannel(ObjectMapper mapper, ScheduledExecutorService scheduler,
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

    @Override
    public WsSession current() {
        return current;
    }

    public boolean isConnected() {
        WsSession s = current;
        return s != null && s.isOpen();
    }

    /**
     * Wait for an open session up to the given timeout by polling the current
     * session. Never parks an outbound send: the send path fails fast.
     *
     * @return future with the open session, or TimeoutException when none
     *         appears within the timeout
     */
    public CompletableFuture<WsSession> awaitSession(Duration timeout) {
        WsSession s = current;
        if (s != null && s.isOpen()) {
            return CompletableFuture.completedFuture(s);
        }
        Duration wait = timeout != null ? timeout : DEFAULT_TIMEOUT;
        CompletableFuture<WsSession> result = new CompletableFuture<>();
        scheduleSessionPoll(result, System.nanoTime() + wait.toNanos());
        return result;
    }

    private void scheduleSessionPoll(CompletableFuture<WsSession> result, long deadlineNanos) {
        if (result.isDone()) {
            return;
        }
        WsSession s = current;
        if (s != null && s.isOpen()) {
            result.complete(s);
            return;
        }
        if (shutdown) {
            result.completeExceptionally(new IllegalStateException("RpcChannel is shut down"));
            return;
        }
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            result.completeExceptionally(new TimeoutException("No open WebSocket session within timeout"));
            return;
        }
        long delayMillis = Math.max(1,
                Math.min(SESSION_POLL_INTERVAL.toMillis(), TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
        try {
            scheduler.schedule(() -> scheduleSessionPoll(result, deadlineNanos), delayMillis, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            result.completeExceptionally(new IllegalStateException("RpcChannel is shut down"));
        }
    }

    public void onClose() {
        onClose(new RuntimeException("WebSocket connection closed"));
    }

    public ObjectMapper getObjectMapper() {
        return mapper;
    }

    public <Req, Res> void registerHandler(String type, Class<Req> requestClass,
            Function<RequestContext<Req>, Res> handler) {
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

    public CompletableFuture<Void> subscribe(String event, Object data,
            Consumer<JsonNode> handler) {
        return subscribe(UUID.randomUUID(), event, data, handler, DEFAULT_TIMEOUT);
    }

    public CompletableFuture<Void> subscribe(String event, Object data,
            Consumer<JsonNode> handler, Duration timeout) {
        return subscribe(UUID.randomUUID(), event, data, handler, timeout);
    }

    public CompletableFuture<Void> subscribe(UUID listenId, String event, Object data,
            Consumer<JsonNode> handler) {
        return subscribe(listenId, event, data, handler, DEFAULT_TIMEOUT);
    }

    public CompletableFuture<Void> subscribe(UUID listenId, String event, Object data,
            Consumer<JsonNode> handler, Duration timeout) {
        return subscriptions.subscribe(listenId, event, data, handler, timeout);
    }

    /**
     * If the event stream is already closed, the callback runs immediately.
     */
    public void onSubscriptionClose(UUID listenId, Runnable callback) {
        subscriptions.onSubscriptionClose(listenId, callback);
    }

    public void unsubscribe(UUID listenId) {
        unsubscribe(listenId, null);
    }

    public void unsubscribe(UUID listenId, String reason) {
        subscriptions.unsubscribe(listenId, reason);
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
        InboundFrame<WsMessage<JsonNode>> ctx = new InboundFrame<>(message, from);
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
            case WsProtocol.SUBSCRIBE_TYPE:
            case WsProtocol.UNSUBSCRIBE_TYPE:
                if (!requireEvent(message)) {
                    return;
                }
                subscriptions.handleControl(ctx);
                return;
            case WsProtocol.SUBSCRIBED_TYPE:
            case WsProtocol.EVENT_TYPE:
            case WsProtocol.SUBSCRIPTION_ENDED_TYPE:
            case WsProtocol.SUBSCRIPTION_ERROR_TYPE:
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
        onClose(new RuntimeException("RpcChannel shutdown"));
        scheduler.shutdownNow();
    }
}
