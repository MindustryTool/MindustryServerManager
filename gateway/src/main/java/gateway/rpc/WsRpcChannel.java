package gateway.rpc;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.NullNode;

import gateway.WsMessage;
import gateway.session.WsSession;
import gateway.stream.FileChunkReceiver;
import gateway.stream.FileChunkStreamer;
import gateway.stream.FileTransferHeader;
import lombok.Data;

public class WsRpcChannel {

    private static final Logger LOG = Logger.getLogger(WsRpcChannel.class.getName());
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration SESSION_WAIT = Duration.ofMinutes(5);

    /** Control type carrying stream metadata ahead of binary chunks. */
    public static final String STREAM_START_TYPE = "stream-start";
    /** Control type closing a stream after its binary chunks. */
    public static final String STREAM_DONE_TYPE = "stream-done";
    /** Control type aborting a live stream (fire-and-forget notification). */
    public static final String STREAM_ABORT_TYPE = "stream-abort";
    /** Control type for subscription requests (reserved, never usable as application type). */
    public static final String SUBSCRIBE_TYPE = "subscribe";
    /** Control type for unsubscription requests (reserved, never usable as application type). */
    public static final String UNSUBSCRIBE_TYPE = "unsubscribe";
    /** Receiver slot idle window, refreshed on every stream frame. */
    static final Duration SLOT_TIMEOUT = Duration.ofSeconds(60);
    /** Absolute bound on slot life from reserve. */
    static final Duration SLOT_CEILING = Duration.ofSeconds(300);
    /** Largest reassembled stream accepted before failing loud (32 MiB). */
    public static final int MAX_STREAM_BYTES = 32 * 1024 * 1024;

    private static final TypeReference<WsMessage<JsonNode>> MESSAGE_TYPE = new TypeReference<WsMessage<JsonNode>>() {
    };

    private final ObjectMapper mapper;
    private final ScheduledExecutorService scheduler;
    private final Executor handlerExecutor;

    private final Map<UUID, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final Map<UUID, ScheduledFuture<?>> timeouts = new ConcurrentHashMap<>();
    private final Map<String, HandlerEntry<?, ?>> handlers = new ConcurrentHashMap<>();
    private final Map<String, StreamHandlerEntry<?, ?>> streamHandlers = new ConcurrentHashMap<>();
    private final Map<UUID, StreamSlot> streamSlots = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> senderStreams = new ConcurrentHashMap<>();
    private final Map<UUID, ScheduledFuture<?>> streamTimeouts = new ConcurrentHashMap<>();
    private final FileChunkReceiver streamReceiver = new FileChunkReceiver();

    // Subscription tracking (client-side)
    private final Map<UUID, ClientSubscriptionSlot> clientSubscriptions = new ConcurrentHashMap<>();

    // Subscription tracking (server-side)
    private final Map<String, SubscriptionHandlerEntry> subscriptionHandlers = new ConcurrentHashMap<>();
    private final Map<UUID, ServerSubscriptionSlot> serverSubscriptions = new ConcurrentHashMap<>();

    private volatile CompletableFuture<WsSession> ready = new CompletableFuture<>();

    public static WsRpcChannel create() {
        return new WsRpcChannel(defaultMapper(), defaultScheduler(), Runnable::run);
    }

    public static WsRpcChannel withExecutor(Executor handlerExecutor) {
        java.util.Objects.requireNonNull(handlerExecutor, "handlerExecutor");
        return new WsRpcChannel(defaultMapper(), defaultScheduler(), handlerExecutor);
    }

    public static WsRpcChannel withMapper(ObjectMapper mapper, Executor handlerExecutor) {
        java.util.Objects.requireNonNull(mapper, "mapper");
        java.util.Objects.requireNonNull(handlerExecutor, "handlerExecutor");
        return new WsRpcChannel(mapper, defaultScheduler(), handlerExecutor);
    }

    private WsRpcChannel(ObjectMapper mapper, ScheduledExecutorService scheduler,
            Executor handlerExecutor) {
        this.mapper = mapper;
        this.scheduler = scheduler;
        this.handlerExecutor = handlerExecutor;
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
        synchronized (this) {
            Objects.requireNonNull(session, "session");
            if (!session.isOpen()) {
                throw new IllegalStateException("Cannot signal open for a closed session");
            }

            if (ready.isDone() && ready.getNow(null) != session) {
                throw new IllegalStateException("Duplicate onOpen without onClose");
            }

            ready.complete(session);
        }
    }

    public void onClose() {
        synchronized (this) {
            onClose(new RuntimeException("WebSocket connection closed"));
        }
    }

    public WsSession getSession() {
        return ready.getNow(null);
    }

    public ObjectMapper getObjectMapper() {
        return mapper;
    }

    public <Req, Res> void registerHandler(String type, Class<Req> requestClass, Function<Req, Res> handler) {
        if (type == null || handler == null) {
            throw new IllegalArgumentException("type and handler must not be null");
        }
        rejectStreamControlType(type);
        handlers.put(type, new HandlerEntry<>(requestClass, handler));
    }

    public void unregisterHandler(String type) {
        handlers.remove(type);
    }

    public boolean hasHandler(String type) {
        return handlers.containsKey(type);
    }

    public int pendingCount() {
        return pending.size();
    }

    public <Meta, Res> void registerStreamHandler(String type, Class<Meta> metaClass, Class<Res> responseType,
            BiFunction<Meta, byte[], Res> handler) {
        if (type == null || handler == null) {
            throw new IllegalArgumentException("type and handler must not be null");
        }
        rejectStreamControlType(type);
        streamHandlers.put(type, new StreamHandlerEntry<>(metaClass, responseType, handler));
    }

    public void unregisterStreamHandler(String type) {
        streamHandlers.remove(type);
    }

    public boolean hasStreamHandler(String type) {
        return streamHandlers.containsKey(type);
    }

    // ===== Subscription API (server-side) =====

    /**
     * Subscription request context holding deserialized params and the PushHandle.
     */
    public record SubscriptionRequest<Params>(Params params, PushHandle handle) {
        public SubscriptionRequest {
            Objects.requireNonNull(handle, "handle");
        }
    }

    /**
     * Control handle for a single subscription. Allows pushing events to the subscriber,
     * ending the subscription cleanly or with an error, and registering cleanup callbacks.
     */
    public interface PushHandle {
        /**
         * Push an event to this subscriber.
         *
         * @param event the event object to send (will be serialized as JSON)
         */
        void push(Object event);

        /**
         * End the subscription cleanly. No frame is sent to the client.
         * Invokes {@link #onClose(Runnable)} callbacks.
         */
        void complete();

        /**
         * End the subscription with an error. Sends an error frame to the client.
         * Invokes {@link #onClose(Runnable)} callbacks.
         *
         * @param reason error reason sent to the client
         */
        void fail(String reason);

        /**
         * @return true if the subscription has been closed (by client unsubscribe,
         *         server fail/complete, or connection loss)
         */
        boolean isClosed();

        /**
         * Register a callback to be invoked when the subscription ends.
         * If already closed, the callback runs immediately.
         *
         * @param callback the cleanup action
         */
        void onClose(Runnable callback);
    }

    /**
     * Register a subscription handler for an event type with async initialization.
     *
     * @param eventType the event type name (e.g., "usage")
     * @param paramsClass the class to deserialize the subscription's data payload
     * @param onSubscribe function called once per subscription, receives SubscriptionRequest,
     *        returns a future completing when subscription is accepted
     */
    public <Params> void registerSubscriptionHandler(String eventType, Class<Params> paramsClass,
            Function<SubscriptionRequest<Params>, CompletableFuture<Void>> onSubscribe) {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(paramsClass, "paramsClass");
        Objects.requireNonNull(onSubscribe, "onSubscribe");
        rejectStreamControlType(eventType);
        if (subscriptionHandlers.containsKey(eventType)) {
            throw new IllegalArgumentException("Subscription handler already registered for type: " + eventType);
        }

        Function<SubscriptionRequest<Object>, CompletableFuture<Void>> adapted = req ->
                onSubscribe.apply(new SubscriptionRequest<>(paramsClass.cast(req.params()), req.handle()));
        subscriptionHandlers.put(eventType, new SubscriptionHandlerEntry(paramsClass, adapted));
    }

    /**
     * Register a subscription handler for an event type with synchronous initialization.
     *
     * @param eventType the event type name
     * @param paramsClass the class to deserialize parameters
     * @param onSubscribe consumer called once per subscription
     */
    public void unregisterSubscriptionHandler(String eventType) {
        subscriptionHandlers.remove(eventType);
    }

    /**
     * Check if a subscription handler is registered for the given event type.
     *
     * @param eventType the event type to check
     * @return true if a handler is registered
     */
    public boolean hasSubscriptionHandler(String eventType) {
        return subscriptionHandlers.containsKey(eventType);
    }

    public int pendingStreamCount() {
        return senderStreams.size() + streamSlots.size();
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
        java.util.Objects.requireNonNull(streamId, "streamId");
        String reason = "Stream aborted: " + streamId;
        boolean removed = discardStream(streamId, reason);
        if (removed) {
            try {
                sendRaw(getSession(),
                        WsMessage.<StreamAbort>create(STREAM_ABORT_TYPE)
                                .withPayload(new StreamAbort(streamId, reason)));
            } catch (RuntimeException e) {
                LOG.log(Level.FINE, "Failed to emit stream abort for " + streamId, e);
            }
        }
        return removed;
    }

    /**
     * Discard sender and receiver state for a stream without notifying anyone.
     * Shared by local abort and inbound abort handling.
     *
     * @return true when a sender or receiver slot was discarded
     */
    private boolean discardStream(UUID streamId, String reason) {
        boolean removed = false;
        UUID startId = senderStreams.remove(streamId);
        if (startId != null) {
            removed = true;
            failPending(startId, new RuntimeException(reason));
        }
        StreamSlot slot = streamSlots.remove(streamId);
        if (slot != null) {
            removed = true;
            cancelStreamTimeout(streamId);
            streamReceiver.abort(streamId);
            if (slot.replyRequestId != null) {
                failReplyRequest(slot, reason);
            }
        }
        return removed;
    }

    /**
     * Handle an inbound {@code stream-abort} notification: discard, settle, stay
     * silent.
     */
    private void handleStreamAbort(WsMessage<JsonNode> message) {
        final StreamAbort abort;
        try {
            abort = mapper.treeToValue(message.getPayload(), StreamAbort.class);
        } catch (JsonProcessingException e) {
            LOG.log(Level.WARNING, "Dropping malformed stream abort frame: " + e.getMessage(), e);
            return;
        }
        if (abort == null || abort.streamId() == null) {
            LOG.warning("Dropping stream abort with missing streamId");
            return;
        }
        String reason = abort.reason() != null ? abort.reason() : "Peer aborted stream " + abort.streamId();
        boolean removed = discardStream(abort.streamId(), reason);
        if (!removed) {
            LOG.fine("Dropping abort for unknown stream: " + abort.streamId());
        }
    }

    /**
     * Handle an inbound {@code subscribe} request (server-side).
     */
    private void handleSubscribe(WsMessage<JsonNode> message) {
        UUID subscribeId = message.getId();
        final SubscribePayload payload;
        try {
            payload = mapper.treeToValue(message.getPayload(), SubscribePayload.class);
        } catch (JsonProcessingException e) {
            LOG.log(Level.WARNING, "Dropping malformed subscribe frame: " + e.getMessage(), e);
            sendRaw(getSession(), errorFor(message.getId(), SUBSCRIBE_TYPE,
                    "Malformed subscribe frame: " + e.getMessage()));
            return;
        }
        if (payload == null || payload.eventType() == null) {
            LOG.warning("Dropping subscribe with missing eventType");
            sendRaw(getSession(), errorFor(message.getId(), SUBSCRIBE_TYPE,
                    "Subscribe is missing required field: eventType"));
            return;
        }

        // Look up server handler
        SubscriptionHandlerEntry entry = subscriptionHandlers.get(payload.eventType());
        if (entry == null) {
            LOG.info("No subscription handler for type: " + payload.eventType());
            sendRaw(getSession(), errorFor(message.getId(), payload.eventType(),
                    "unknown subscription type: " + payload.eventType()));
            return;
        }

        Object params;
        try {
            params = convertSubscriptionParams(payload.data(), entry);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to deserialize subscription params", e);
            sendRaw(getSession(), errorFor(message.getId(), payload.eventType(),
                    "Invalid subscription parameters: " + e.getMessage()));
            return;
        }

        DefaultPushHandle handle = new DefaultPushHandle(subscribeId, payload.eventType());
        ServerSubscriptionSlot slot = new ServerSubscriptionSlot(
                subscribeId, payload.eventType(), params, handle);
        if (serverSubscriptions.putIfAbsent(subscribeId, slot) != null) {
            handle.fail("Duplicate subscription ID");
            return;
        }

        handle.onClose(() -> serverSubscriptions.remove(subscribeId));

        CompletableFuture<Void> future;
        try {
            future = entry.onSubscribe.apply(new SubscriptionRequest<>(params, handle));
        } catch (Exception e) {
            LOG.log(Level.WARNING, "onSubscribe threw exception", e);
            serverSubscriptions.remove(subscribeId);
            handle.fail("Subscription handler failed: " + e.getMessage());
            return;
        }

        if (future == null) {
            sendRaw(getSession(), WsMessage.create(payload.eventType())
                    .setResponseOf(subscribeId)
                    .withPayload(NullNode.getInstance()));
            return;
        }

        future.whenComplete((v, err) -> {
            if (err != null) {
                serverSubscriptions.remove(subscribeId);
                String detail = err.getMessage() != null ? err.getMessage() : err.toString();
                handle.fail(detail);
                return;
            }
            sendRaw(getSession(), WsMessage.create(payload.eventType())
                    .setResponseOf(subscribeId)
                    .withPayload(NullNode.getInstance()));
        });
    }

    /**
     * Handle an inbound {@code unsubscribe} notification (server-side).
     */
    private void handleUnsubscribe(WsMessage<JsonNode> message) {
        UUID subscribeId = message.getResponseOf();
        if (subscribeId == null) {
            LOG.fine("Dropping unsubscribe with missing responseOf");
            return;
        }
        ServerSubscriptionSlot slot = serverSubscriptions.remove(subscribeId);
        if (slot == null) {
            LOG.fine("Dropping unsubscribe for unknown subscription: " + subscribeId);
            return;
        }
        slot.closed = true;
        slot.handle.complete();
        for (Runnable cb : slot.onCloseCallbacks) {
            try {
                cb.run();
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Subscription onClose callback failed", e);
            }
        }
    }

    /**
     * Route event frame to client subscription handler.
     */
    private void handleSubscriptionEvent(WsMessage<JsonNode> message) {
        UUID subscribeId = message.getResponseOf();
        if (subscribeId == null) {
            LOG.fine("Dropping event frame without responseOf");
            return;
        }
        ClientSubscriptionSlot slot = clientSubscriptions.get(subscribeId);
        if (slot == null) {
            LOG.fine("Dropping event for unknown subscription: " + subscribeId);
            return;
        }
        if (slot.closed) {
            LOG.fine("Dropping event for closed subscription: " + subscribeId);
            return;
        }
        if (slot.ackFuture != null && !slot.ackFuture.isDone()) {
            slot.ackFuture.complete(null);
        }
        JsonNode payload = message.getPayload();
        if (payload != null && !payload.isNull()) {
            try {
                slot.handler.accept(payload);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Subscription handler failed for " + slot.eventType, e);
            }
        }
    }

    /**
     * Handle server-initiated error frame for a subscription (client-side).
     */
    private void handleSubscriptionError(WsMessage<JsonNode> message) {
        UUID subscribeId = message.getResponseOf();
        if (subscribeId == null) {
            return;
        }
        ClientSubscriptionSlot slot = clientSubscriptions.remove(subscribeId);
        if (slot == null) {
            return;
        }
        slot.closed = true;
        if (slot.timeoutTask != null) {
            slot.timeoutTask.cancel(false);
        }
        String detail = message.getPayload() == null ? "subscription failed"
                : message.getPayload().isTextual() ? message.getPayload().asText()
                : message.getPayload().toString();
        if (slot.ackFuture != null && !slot.ackFuture.isDone()) {
            slot.ackFuture.completeExceptionally(new RuntimeException(detail));
        }
        for (Runnable cb : slot.onCloseCallbacks) {
            try {
                cb.run();
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Subscription onClose callback failed", e);
            }
        }
    }

    private static void rejectStreamControlType(String type) {
        if (STREAM_START_TYPE.equals(type) || STREAM_DONE_TYPE.equals(type)
                || STREAM_ABORT_TYPE.equals(type)
                || SUBSCRIBE_TYPE.equals(type)
                || UNSUBSCRIBE_TYPE.equals(type)) {
            throw new IllegalArgumentException("type is reserved for stream/subscription control frames: " + type);
        }
    }

    public CompletableFuture<Void> sendRequest(String type, Object payload) {
        return sendRequest(type, payload, Void.class, DEFAULT_TIMEOUT);
    }

    public <Res> CompletableFuture<Res> sendRequest(String type, Object payload, Class<Res> responseType) {
        return sendRequest(type, payload, responseType, DEFAULT_TIMEOUT);
    }

    public <Res> CompletableFuture<Res> sendRequest(String type, Object payload, Class<Res> responseType,
            Duration timeout) {
        WsMessage<?> request = WsMessage.create(type).withPayload(payload);
        UUID id = request.getId();
        Duration effectiveTimeout = timeout != null ? timeout : DEFAULT_TIMEOUT;

        CompletableFuture<JsonNode> sent = ready.thenApply(s -> s)
                .orTimeout(SESSION_WAIT.toMillis(), TimeUnit.MILLISECONDS)
                .thenCompose(s -> transmit(id, request, effectiveTimeout, type, s));

        return sent.thenApply(node -> convert(node, responseType));
    }

    private CompletableFuture<JsonNode> transmit(UUID id, WsMessage<?> request, Duration effectiveTimeout,
            String type, WsSession session) {
        CompletableFuture<JsonNode> raw = new CompletableFuture<>();
        pending.put(id, raw);

        ScheduledFuture<?> timeoutTask = scheduler.schedule(() -> {
            CompletableFuture<JsonNode> removed = pending.remove(id);
            timeouts.remove(id);
            if (removed != null && !removed.isDone()) {
                removed.completeExceptionally(
                        new TimeoutException("RPC request timed out: type=" + type + " id=" + id));
            }
        }, effectiveTimeout.toMillis(), TimeUnit.MILLISECONDS);
        timeouts.put(id, timeoutTask);
        raw.whenComplete((res, err) -> {
            ScheduledFuture<?> t = timeouts.remove(id);
            if (t != null) {
                t.cancel(false);
            }
            pending.remove(id);
        });

        try {
            if (!session.isOpen()) {
                failPending(id, new IllegalStateException("No open WebSocket session for RPC request: " + type));
            } else {
                session.sendText(mapper.writeValueAsString(request));
            }
        } catch (JsonProcessingException e) {
            failPending(id, e);
        } catch (RuntimeException e) {
            failPending(id, e);
        }
        return raw;
    }

    public void sendNotification(String type, Object payload) {
        WsMessage<?> message;
        try {
            message = WsMessage.create(type).withPayload(payload);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to queue notification: " + type, e);
            return;
        }
        ready.thenApply(s -> s)
                .orTimeout(SESSION_WAIT.toMillis(), TimeUnit.MILLISECONDS)
                .thenAccept(s -> {
                    if (!s.isOpen()) {
                        return;
                    }
                    try {
                        s.sendText(mapper.writeValueAsString(message));
                    } catch (Exception e) {
                        LOG.log(Level.WARNING, "Failed to send queued notification: " + type, e);
                    }
                })
                .exceptionally(err -> {
                    Throwable cause = err instanceof java.util.concurrent.CompletionException ce
                            && ce.getCause() != null ? ce.getCause() : err;
                    if (cause instanceof TimeoutException) {
                        LOG.fine("Dropping notification (session wait timed out): " + type);
                    } else {
                        LOG.info("Dropping notification (connection closed): " + type);
                    }
                    return null;
                });
    }

    // ===== Subscription API (client-side) =====

    /**
     * Subscribe to an event type with parameters.
     *
     * @param eventType the event type to subscribe to
     * @param data optional parameters for this subscription
     * @param handler invoked for each event received
     * @return future that completes when the subscription is acknowledged (first event received)
     */
    public CompletableFuture<Void> subscribe(String eventType, Object data,
            Consumer<JsonNode> handler) {
        return subscribe(UUID.randomUUID(), eventType, data, handler, DEFAULT_TIMEOUT);
    }

    /**
     * Subscribe to an event type with parameters and custom timeout.
     *
     * @param eventType the event type to subscribe to
     * @param data optional parameters for this subscription
     * @param handler invoked for each event received
     * @param timeout operation timeout
     * @return future that completes when the subscription is acknowledged (first event received)
     */
    public CompletableFuture<Void> subscribe(String eventType, Object data,
            Consumer<JsonNode> handler, Duration timeout) {
        return subscribe(UUID.randomUUID(), eventType, data, handler, timeout);
    }

    /**
     * Subscribe to an event type with explicit subscription ID and default timeout.
     *
     * @param subscriptionId the explicit subscription ID
     * @param eventType the event type to subscribe to
     * @param data optional parameters for this subscription
     * @param handler invoked for each event received
     * @return future that completes when the subscription is acknowledged
     */
    public CompletableFuture<Void> subscribe(UUID subscriptionId, String eventType, Object data,
            Consumer<JsonNode> handler) {
        return subscribe(subscriptionId, eventType, data, handler, DEFAULT_TIMEOUT);
    }

    /**
     * Subscribe to an event type with explicit subscription ID and custom timeout.
     *
     * @param subscriptionId the explicit subscription ID
     * @param eventType the event type to subscribe to
     * @param data optional parameters for this subscription
     * @param handler invoked for each event received
     * @param timeout operation timeout
     * @return future that completes when the subscription is acknowledged
     */
    public CompletableFuture<Void> subscribe(UUID subscriptionId, String eventType, Object data,
            Consumer<JsonNode> handler, Duration timeout) {
        Objects.requireNonNull(subscriptionId, "subscriptionId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(handler, "handler");
        WsMessage<?> request = WsMessage.create(SUBSCRIBE_TYPE)
                .setId(subscriptionId)
                .withPayload(mapper.valueToTree(new SubscribePayload(eventType, data)));
        Duration effectiveTimeout = timeout != null ? timeout : DEFAULT_TIMEOUT;

        CompletableFuture<Void> ackFuture = new CompletableFuture<>();
        ClientSubscriptionSlot slot = new ClientSubscriptionSlot(subscriptionId, eventType, handler, ackFuture);
        clientSubscriptions.put(subscriptionId, slot);

        ScheduledFuture<?> timeoutTask = scheduler.schedule(() -> {
            ClientSubscriptionSlot removed = clientSubscriptions.remove(subscriptionId);
            if (removed != null && !removed.ackFuture.isDone()) {
                removed.closed = true;
                removed.ackFuture.completeExceptionally(
                        new TimeoutException("Subscription timed out: type=" + eventType + " id=" + subscriptionId));
            }
        }, effectiveTimeout.toMillis(), TimeUnit.MILLISECONDS);
        slot.timeoutTask = timeoutTask;

        ackFuture.whenComplete((res, err) -> {
            ScheduledFuture<?> t = slot.timeoutTask;
            if (t != null) {
                t.cancel(false);
            }
        });

        ready.thenApply(s -> s)
                .orTimeout(SESSION_WAIT.toMillis(), TimeUnit.MILLISECONDS)
                .thenAccept(s -> {
                    if (!s.isOpen()) {
                        clientSubscriptions.remove(subscriptionId);
                        ackFuture.completeExceptionally(
                                new IllegalStateException("No open WebSocket session for subscription: " + eventType));
                        return;
                    }
                    try {
                        s.sendText(mapper.writeValueAsString(request));
                    } catch (Exception e) {
                        clientSubscriptions.remove(subscriptionId);
                        ackFuture.completeExceptionally(e);
                    }
                })
                .exceptionally(err -> {
                    clientSubscriptions.remove(subscriptionId);
                    Throwable cause = err instanceof java.util.concurrent.CompletionException ce
                            && ce.getCause() != null ? ce.getCause() : err;
                    ackFuture.completeExceptionally(cause);
                    return null;
                });

        return ackFuture;
    }

    /**
     * Register a callback to be invoked when a client subscription closes.
     * If already closed, the callback runs immediately.
     *
     * @param subscriptionId the subscription ID
     * @param callback cleanup callback
     */
    public void onSubscriptionClose(UUID subscriptionId, Runnable callback) {
        Objects.requireNonNull(subscriptionId, "subscriptionId");
        Objects.requireNonNull(callback, "callback");
        ClientSubscriptionSlot slot = clientSubscriptions.get(subscriptionId);
        if (slot == null || slot.closed) {
            try {
                callback.run();
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Subscription onClose callback failed", e);
            }
        } else {
            slot.onCloseCallbacks.add(callback);
        }
    }

    /**
     * Unsubscribe from a subscription by its request ID.
     *
     * @param requestId the ID returned by the original subscribe call
     */
    public void unsubscribe(UUID requestId) {
        unsubscribe(requestId, null);
    }

    /**
     * Unsubscribe from a subscription by its request ID with a reason.
     *
     * @param requestId the ID returned by the original subscribe call
     * @param reason optional reason for unsubscribing
     */
    public void unsubscribe(UUID requestId, String reason) {
        Objects.requireNonNull(requestId, "requestId");
        ClientSubscriptionSlot slot = clientSubscriptions.remove(requestId);
        if (slot == null) {
            return;
        }
        slot.closed = true;
        if (slot.timeoutTask != null) {
            slot.timeoutTask.cancel(false);
        }
        for (Runnable cb : slot.onCloseCallbacks) {
            try {
                cb.run();
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Subscription onClose callback failed", e);
            }
        }

        WsMessage<?> message = WsMessage.create(UNSUBSCRIBE_TYPE)
                .withPayload(reason != null ? Map.of("reason", reason) : Map.of())
                .setResponseOf(requestId);

        ready.thenApply(s -> s)
                .orTimeout(SESSION_WAIT.toMillis(), TimeUnit.MILLISECONDS)
                .thenAccept(s -> {
                    if (!s.isOpen()) {
                        return;
                    }
                    try {
                        s.sendText(mapper.writeValueAsString(message));
                    } catch (Exception e) {
                        LOG.log(Level.WARNING, "Failed to send unsubscribe", e);
                    }
                })
                .exceptionally(err -> null);
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
        java.util.Objects.requireNonNull(data, "data");
        ByteBuffer dup = data.duplicate();
        byte[] bytes = new byte[dup.remaining()];
        dup.get(bytes);
        return sendStreamBytes(type, metadata, bytes, responseType, timeout);
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
        java.util.Objects.requireNonNull(data, "data");
        return sendStreamBytes(type, metadata, data, responseType, timeout);
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
        java.util.Objects.requireNonNull(data, "data");
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = data.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return sendStreamBytes(type, metadata, out.toByteArray(), responseType, timeout);
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to read stream data for type=" + type, e);
        }
    }

    private <Res> CompletableFuture<Res> sendStreamBytes(String type, Object metadata, byte[] bytes,
            Class<Res> responseType, Duration timeout) {
        java.util.Objects.requireNonNull(type, "type");
        java.util.Objects.requireNonNull(bytes, "bytes");
        rejectStreamControlType(type);
        Duration effectiveTimeout = timeout != null ? timeout : DEFAULT_TIMEOUT;
        UUID streamId = UUID.randomUUID();
        String sha256 = FileChunkStreamer.sha256Hex(bytes);
        List<ByteBuffer> frames = FileChunkStreamer.chunk(streamId, bytes);
        JsonNode metaNode = metadata == null ? NullNode.getInstance() : mapper.valueToTree(metadata);
        StreamStart start = new StreamStart(streamId, type, metaNode, frames.size(), sha256);
        WsMessage<StreamStart> startMessage;
        WsMessage<StreamDone> doneMessage;
        try {
            startMessage = WsMessage.<StreamStart>create(STREAM_START_TYPE).withPayload(start);
            doneMessage = WsMessage.<StreamDone>create(STREAM_DONE_TYPE)
                    .withPayload(new StreamDone(streamId, sha256));
        } catch (RuntimeException e) {
            CompletableFuture<Res> failed = new CompletableFuture<>();
            failed.completeExceptionally(e);
            return failed;
        }
        UUID startId = startMessage.getId();
        String startJson;
        String doneJson;
        try {
            startJson = mapper.writeValueAsString(startMessage);
            doneJson = mapper.writeValueAsString(doneMessage);
        } catch (JsonProcessingException e) {
            CompletableFuture<Res> failed = new CompletableFuture<>();
            failed.completeExceptionally(e);
            return failed;
        }

        CompletableFuture<JsonNode> raw = new CompletableFuture<>();
        pending.put(startId, raw);
        senderStreams.put(streamId, startId);
        ScheduledFuture<?> timeoutTask = scheduler.schedule(() -> {
            senderStreams.remove(streamId);
            CompletableFuture<JsonNode> removed = pending.remove(startId);
            timeouts.remove(startId);
            if (removed != null && !removed.isDone()) {
                removed.completeExceptionally(
                        new TimeoutException("RPC stream timed out: type=" + type + " stream=" + streamId));
            }
        }, effectiveTimeout.toMillis(), TimeUnit.MILLISECONDS);
        timeouts.put(startId, timeoutTask);
        raw.whenComplete((res, err) -> {
            ScheduledFuture<?> t = timeouts.remove(startId);
            if (t != null) {
                t.cancel(false);
            }
            pending.remove(startId);
            senderStreams.remove(streamId);
        });

        ready.thenApply(s -> s)
                .orTimeout(SESSION_WAIT.toMillis(), TimeUnit.MILLISECONDS)
                .thenAccept(s -> emitStream(startId, type, streamId, startJson, frames, doneJson, s))
                .exceptionally(err -> {
                    failPending(startId, unwrapCompletion(err));
                    return null;
                });
        return raw.thenApply(node -> convert(node, responseType));
    }

    private void emitStream(UUID startId, String type, UUID streamId, String startJson, List<ByteBuffer> frames,
            String doneJson, WsSession session) {
        try {
            if (!session.isOpen()) {
                failPending(startId, new IllegalStateException("No open WebSocket session for RPC stream: " + type));
                return;
            }
            session.sendText(startJson);
            for (ByteBuffer frame : frames) {
                if (!session.isOpen()) {
                    failPending(startId,
                            new IllegalStateException("Session closed mid-stream for RPC stream: " + type));
                    return;
                }
                session.sendBinary(frame);
            }
            session.sendText(doneJson);
        } catch (RuntimeException e) {
            failPending(startId, e);
        }
    }

    private static Throwable unwrapCompletion(Throwable err) {
        if (err instanceof java.util.concurrent.CompletionException ce && ce.getCause() != null) {
            return ce.getCause();
        }
        return err;
    }

    /**
     * Answer an incoming request with a stream instead of a single frame.
     *
     * <p>
     * The requester's pending future resolves with the assembled bytes once
     * its peer processes the reply's {@code done} envelope.
     */
    private void emitReplyStream(WsMessage<JsonNode> request, StreamReply reply) {
        byte[] bytes = reply.data();
        UUID streamId = UUID.randomUUID();
        String sha256 = FileChunkStreamer.sha256Hex(bytes);
        List<ByteBuffer> frames = FileChunkStreamer.chunk(streamId, bytes);
        JsonNode metaNode = reply.metadata() == null ? NullNode.getInstance()
                : mapper.valueToTree(reply.metadata());
        WsSession s = getSession();
        if (s == null || !s.isOpen()) {
            LOG.info("Dropping stream reply, no open session for type=" + request.getType());
            return;
        }
        try {
            WsMessage<StreamStart> start = new WsMessage<>();
            start.setId(UUID.randomUUID())
                    .setType(STREAM_START_TYPE)
                    .setResponseOf(request.getId())
                    .setPayload(new StreamStart(streamId, request.getType(), metaNode, frames.size(), sha256));
            s.sendText(mapper.writeValueAsString(start));
            for (ByteBuffer frame : frames) {
                if (!s.isOpen()) {
                    LOG.info("Dropping stream reply mid-stream for type=" + request.getType());
                    return;
                }
                s.sendBinary(frame);
            }
            WsMessage<StreamDone> done = new WsMessage<>();
            done.setId(UUID.randomUUID())
                    .setType(STREAM_DONE_TYPE)
                    .setResponseOf(request.getId())
                    .setPayload(new StreamDone(streamId, sha256));
            s.sendText(mapper.writeValueAsString(done));
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to send stream reply", e);
            String detail = e.getMessage() != null ? e.getMessage() : e.toString();
            sendRaw(getSession(), errorFor(request.getId(), request.getType(), detail));
        }
    }

    @SuppressWarnings("unchecked")
    private <Res> Res convert(JsonNode node, Class<Res> responseType) {
        if (responseType == null || responseType == Void.class || responseType == Void.TYPE) {
            return null;
        }
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (responseType == JsonNode.class) {
            return (Res) node;
        }
        if (responseType == byte[].class && node.isBinary()) {
            try {
                return (Res) node.binaryValue();
            } catch (java.io.IOException e) {
                throw new RuntimeException("Cannot read binary RPC payload: " + e.getMessage(), e);
            }
        }
        if (responseType == String.class && node.isTextual()) {
            return (Res) node.asText();
        }
        try {
            return mapper.treeToValue(node, responseType);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(
                    "Cannot convert RPC payload to " + responseType.getName() + ": " + e.getMessage(), e);
        }
    }

    private void failPending(UUID id, Throwable err) {
        CompletableFuture<JsonNode> f = pending.remove(id);
        ScheduledFuture<?> t = timeouts.remove(id);
        if (t != null) {
            t.cancel(false);
        }
        if (f != null && !f.isDone()) {
            f.completeExceptionally(err);
        }
    }

    public void onTextMessage(String json) {
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
        if (STREAM_START_TYPE.equals(controlType)) {
            handleStreamStart(message);
            return;
        }
        if (STREAM_DONE_TYPE.equals(controlType)) {
            handleStreamDone(message);
            return;
        }
        if (STREAM_ABORT_TYPE.equals(controlType)) {
            handleStreamAbort(message);
            return;
        }
        if (SUBSCRIBE_TYPE.equals(controlType)) {
            handleSubscribe(message);
            return;
        }
        if (UNSUBSCRIBE_TYPE.equals(controlType)) {
            handleUnsubscribe(message);
            return;
        }

        if (message.getResponseOf() != null) {
            UUID responseOf = message.getResponseOf();
            // Check if this is a subscription event/error frame
            ClientSubscriptionSlot subSlot = clientSubscriptions.get(responseOf);
            if (subSlot != null) {
                if (message.isError()) {
                    handleSubscriptionError(message);
                } else {
                    handleSubscriptionEvent(message);
                }
                return;
            }

            CompletableFuture<JsonNode> future = pending.remove(responseOf);
            if (future == null) {
                LOG.fine("No pending RPC for responseOf: " + responseOf + " type: " + message.getType());
                return;
            }
            ScheduledFuture<?> t = timeouts.remove(responseOf);
            if (t != null) {
                t.cancel(false);
            }
            if (message.isError()) {
                JsonNode payload = message.getPayload();
                String detail = payload == null ? "remote error" : payload.toString();
                future.completeExceptionally(new RuntimeException(detail));
            } else {
                future.complete(message.getPayload());
            }
            return;
        }

        String type = message.getType();
        if (type == null) {
            LOG.info("Dropping RPC frame without type");
            return;
        }
        HandlerEntry<?, ?> entry = handlers.get(type);
        if (entry == null) {
            LOG.info("No RPC handler for type: " + type);
            sendRaw(getSession(), message.error("unknown RPC type: " + type));
            return;
        }

        handlerExecutor.execute(() -> invokeHandler(message, entry));
    }

    @SuppressWarnings({ "unchecked" })
    private void invokeHandler(WsMessage<JsonNode> message, HandlerEntry<?, ?> entry) {
        WsSession s = getSession();
        try {
            Object param = convertParam(message.getPayload(), (HandlerEntry<Object, Object>) entry);
            Object result = ((HandlerEntry<Object, Object>) entry).fn.apply(param);
            if (result instanceof StreamReply reply) {
                emitReplyStream(message, reply);
                return;
            }
            WsMessage<?> response = message.response(result);
            sendRaw(s, response);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "RPC handler failed for type=" + message.getType(), e);
            try {
                String detail = e.getMessage() != null ? e.getMessage() : e.toString();
                WsMessage<?> error = message.error(detail);
                sendRaw(s, error);
            } catch (Exception sendError) {
                LOG.log(Level.WARNING, "Failed to send RPC error frame", sendError);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Object convertParam(JsonNode payload, HandlerEntry<?, ?> entry) {
        Class<?> clazz = entry.requestClass;
        if (clazz == null || clazz == Void.class || clazz == Void.TYPE) {
            return null;
        }
        if (payload == null || payload.isNull() || payload.isMissingNode()) {
            return null;
        }
        if (clazz == JsonNode.class) {
            return payload;
        }
        try {
            return mapper.treeToValue(payload, (Class<Object>) clazz);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Cannot deserialize RPC param to " + clazz.getName(), e);
        }
    }

    /**
     * Ingest one binary chunk frame for an announced stream.
     *
     * <p>
     * Unknown streams are dropped with a log. Duplicate chunk indexes are
     * ignored so redelivered frames stay harmless.
     */
    public void onBinaryMessage(ByteBuffer frame) {
        java.util.Objects.requireNonNull(frame, "frame");
        final FileTransferHeader header;
        try {
            header = FileTransferHeader.decode(frame.duplicate());
        } catch (RuntimeException e) {
            failOnNegativeIndex(frame);
            LOG.log(Level.WARNING, "Dropping malformed stream chunk frame: " + e.getMessage(), e);
            return;
        }
        UUID streamId = header.transferId();
        StreamSlot slot = streamSlots.get(streamId);
        if (slot == null) {
            LOG.warning("Dropping chunk for unknown stream: " + streamId);
            return;
        }
        refreshStreamTimeout(streamId);
        final byte[] payload;
        try {
            payload = FileTransferHeader.payloadOf(frame);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Dropping malformed stream chunk payload: " + e.getMessage(), e);
            return;
        }
        synchronized (slot) {
            if (!streamSlots.containsKey(streamId)) {
                return;
            }
            if (header.chunkIndex() >= slot.totalChunks) {
                failLiveSlot(streamId, slot, "Chunk index " + header.chunkIndex() + " outside 0.."
                        + (slot.totalChunks - 1) + " for stream " + streamId);
                return;
            }
            if (slot.seen.putIfAbsent(header.chunkIndex(), payload.length) != null) {
                LOG.fine("Ignoring duplicate chunk " + header.chunkIndex() + " for stream: " + streamId);
                return;
            }
            int total = slot.receivedBytes.addAndGet(payload.length);
            if (total > MAX_STREAM_BYTES) {
                streamSlots.remove(streamId);
                cancelStreamTimeout(streamId);
                streamReceiver.abort(streamId);
                if (slot.replyRequestId != null) {
                    failReplyRequest(slot, "Stream exceeds max bytes (" + MAX_STREAM_BYTES + "): " + streamId);
                } else {
                    sendStreamError(slot, "Stream exceeds max bytes (" + MAX_STREAM_BYTES + "): " + streamId);
                }
                return;
            }
            try {
                streamReceiver.receive(frame);
            } catch (RuntimeException e) {
                streamSlots.remove(streamId);
                cancelStreamTimeout(streamId);
                streamReceiver.abort(streamId);
                LOG.log(Level.WARNING, "Failed to buffer chunk for stream " + streamId, e);
                if (slot.replyRequestId != null) {
                    failReplyRequest(slot,
                            "Failed to buffer chunk for stream " + streamId + ": " + e.getMessage());
                } else {
                    sendStreamError(slot, "Failed to buffer chunk for stream " + streamId + ": " + e.getMessage());
                }
            }
        }
    }

    /**
     * Fail a live receiver slot at ingest time (bad chunk index). The slot is
     * removed atomically; when already gone the frame is simply dropped.
     */
    private void failLiveSlot(UUID streamId, StreamSlot slot, String detail) {
        if (!streamSlots.remove(streamId, slot)) {
            return;
        }
        cancelStreamTimeout(streamId);
        streamReceiver.abort(streamId);
        if (slot.replyRequestId != null) {
            failReplyRequest(slot, detail);
        } else {
            sendStreamError(slot, detail);
        }
    }

    /**
     * A negative chunk index is a stream failure for a known stream, not a
     * silent drop: discard the slot and settle its waiter. Unknown streams
     * and short frames stay drop-with-log.
     */
    private void failOnNegativeIndex(ByteBuffer frame) {
        UUID streamId;
        try {
            ByteBuffer dup = frame.duplicate();
            if (dup.remaining() < FileTransferHeader.HEADER_SIZE
                    || dup.getInt(dup.position() + 16) >= 0) {
                return;
            }
            int pos = dup.position();
            streamId = new UUID(dup.getLong(pos), dup.getLong(pos + 8));
        } catch (RuntimeException e) {
            return;
        }
        StreamSlot slot = streamSlots.get(streamId);
        if (slot != null) {
            failLiveSlot(streamId, slot,
                    "Negative chunk index for stream " + streamId);
        }
    }

    private void handleStreamStart(WsMessage<JsonNode> message) {
        UUID replyTo = message.getResponseOf();
        final StreamStart start;
        try {
            start = mapper.treeToValue(message.getPayload(), StreamStart.class);
        } catch (JsonProcessingException e) {
            LOG.log(Level.WARNING, "Dropping malformed stream start frame: " + e.getMessage(), e);
            if (replyTo != null) {
                failPending(replyTo,
                        new RuntimeException("Malformed reply stream start frame: " + e.getMessage()));
                return;
            }
            sendRaw(getSession(), errorFor(message.getId(), message.getType(),
                    "Malformed stream start frame: " + e.getMessage()));
            return;
        }
        if (start == null || start.streamId() == null || start.streamType() == null || start.sha256() == null
                || start.totalChunks() < 1) {
            LOG.warning("Dropping stream start with missing fields for type: " + message.getType());
            if (replyTo != null) {
                failPending(replyTo, new RuntimeException("Reply stream start is missing required fields"));
                return;
            }
            sendRaw(getSession(), errorFor(message.getId(), message.getType(),
                    "Stream start is missing required fields"));
            return;
        }
        if (replyTo != null) {
            if (!pending.containsKey(replyTo)) {
                LOG.fine("No pending request for reply stream: " + start.streamId());
                return;
            }
            StreamSlot slot = new StreamSlot(start.streamId(), message.getId(), start.streamType(),
                    start.metadata(), start.totalChunks(), start.sha256(), replyTo);
            if (streamSlots.putIfAbsent(start.streamId(), slot) != null) {
                LOG.warning("Dropping duplicate reply stream start: " + start.streamId());
                sendRaw(getSession(),
                        errorFor(replyTo, start.streamType(), "duplicate reply stream start: " + start.streamId()));
                return;
            }
            armStreamTimeout(start.streamId());
            return;
        }
        StreamHandlerEntry<?, ?> entry = streamHandlers.get(start.streamType());
        if (entry == null) {
            LOG.info("No stream handler for type: " + start.streamType());
            sendRaw(getSession(),
                    errorFor(message.getId(), start.streamType(), "unknown stream type: " + start.streamType()));
            return;
        }
        StreamSlot slot = new StreamSlot(start.streamId(), message.getId(), start.streamType(),
                start.metadata(), start.totalChunks(), start.sha256(), null);
        if (streamSlots.putIfAbsent(start.streamId(), slot) != null) {
            LOG.warning("Dropping duplicate stream start: " + start.streamId());
            sendRaw(getSession(),
                    errorFor(message.getId(), start.streamType(), "duplicate stream start: " + start.streamId()));
            return;
        }
        armStreamTimeout(start.streamId());
    }

    private void armStreamTimeout(UUID streamId) {
        scheduleSlotExpiry(streamId);
    }

    /**
     * Re-arm the slot timer after stream progress, bounded by the ceiling.
     * No-op when the slot is already gone (completed, aborted, or timed out).
     */
    private void refreshStreamTimeout(UUID streamId) {
        if (!streamSlots.containsKey(streamId)) {
            return;
        }
        cancelStreamTimeout(streamId);
        scheduleSlotExpiry(streamId);
    }

    /**
     * Delay until slot expiry from elapsed life, or -1 when the ceiling is spent.
     */
    private static long slotExpiryDelayMillis(long elapsedNanos) {
        long remainingNanos = SLOT_CEILING.toNanos() - elapsedNanos;
        if (remainingNanos <= 0) {
            return -1;
        }
        return Math.min(SLOT_TIMEOUT.toMillis(), TimeUnit.NANOSECONDS.toMillis(remainingNanos));
    }

    private void scheduleSlotExpiry(UUID streamId) {
        StreamSlot slot = streamSlots.get(streamId);
        if (slot == null) {
            return;
        }
        long delay = slotExpiryDelayMillis(System.nanoTime() - slot.reservedAtNanos);
        if (delay < 0) {
            expireSlot(streamId, "Stream slot ceiling exceeded (300 s): " + streamId);
            return;
        }
        ScheduledFuture<?>[] holder = new ScheduledFuture<?>[1];
        ScheduledFuture<?> timeoutTask = scheduler.schedule(() -> {
            if (streamTimeouts.get(streamId) != holder[0]) {
                return;
            }
            StreamSlot removed = streamSlots.get(streamId);
            long elapsed = removed == null ? 0 : System.nanoTime() - removed.reservedAtNanos;
            if (removed != null && elapsed < SLOT_CEILING.toNanos()) {
                expireSlot(streamId, "Stream timed out: " + streamId);
            } else if (removed != null) {
                expireSlot(streamId, "Stream slot ceiling exceeded (300 s): " + streamId);
            }
            streamTimeouts.remove(streamId);
        }, delay, TimeUnit.MILLISECONDS);
        holder[0] = timeoutTask;
        streamTimeouts.put(streamId, timeoutTask);
    }

    /** Discard a slot on timeout and settle its waiter. Idempotent. */
    private void expireSlot(UUID streamId, String detail) {
        StreamSlot removed = streamSlots.remove(streamId);
        streamTimeouts.remove(streamId);
        if (removed == null) {
            return;
        }
        streamReceiver.abort(streamId);
        LOG.warning(detail);
        if (removed.replyRequestId != null) {
            failReplyRequest(removed, detail);
        } else {
            sendStreamError(removed, detail);
        }
    }

    /** Fail the request a reply stream was answering, without sending any frame. */
    private void failReplyRequest(StreamSlot slot, String detail) {
        CompletableFuture<JsonNode> future = pending.remove(slot.replyRequestId);
        ScheduledFuture<?> t = timeouts.remove(slot.replyRequestId);
        if (t != null) {
            t.cancel(false);
        }
        if (future != null && !future.isDone()) {
            future.completeExceptionally(new RuntimeException(detail));
        }
    }

    private void handleStreamDone(WsMessage<JsonNode> message) {
        final StreamDone done;
        try {
            done = mapper.treeToValue(message.getPayload(), StreamDone.class);
        } catch (JsonProcessingException e) {
            LOG.log(Level.WARNING, "Dropping malformed stream done frame: " + e.getMessage(), e);
            return;
        }
        if (done == null || done.streamId() == null || done.sha256() == null) {
            LOG.warning("Dropping stream done with missing fields");
            return;
        }
        StreamSlot slot = streamSlots.remove(done.streamId());
        if (slot == null) {
            LOG.fine("No pending stream for done: " + done.streamId());
            return;
        }
        cancelStreamTimeout(done.streamId());
        boolean isReply = slot.replyRequestId != null;
        if (!done.sha256().equalsIgnoreCase(slot.sha256)) {
            streamReceiver.abort(done.streamId());
            if (isReply) {
                failReplyRequest(slot, "Stream checksum header mismatch for stream " + done.streamId());
            } else {
                sendStreamError(slot, "Stream checksum header mismatch for stream " + done.streamId());
            }
            return;
        }
        final byte[] assembled;
        try {
            assembled = streamReceiver.assemble(done.streamId(), slot.totalChunks, slot.sha256);
        } catch (SecurityException e) {
            streamReceiver.abort(done.streamId());
            if (isReply) {
                failReplyRequest(slot, "Checksum mismatch for stream " + done.streamId());
            } else {
                sendStreamError(slot, "Checksum mismatch for stream " + done.streamId());
            }
            return;
        } catch (IllegalStateException e) {
            streamReceiver.abort(done.streamId());
            if (isReply) {
                failReplyRequest(slot, "Incomplete stream " + done.streamId() + ": " + e.getMessage());
            } else {
                sendStreamError(slot, "Incomplete stream " + done.streamId() + ": " + e.getMessage());
            }
            return;
        }
        if (assembled.length > MAX_STREAM_BYTES) {
            if (isReply) {
                failReplyRequest(slot,
                        "Stream exceeds max bytes (" + MAX_STREAM_BYTES + "): " + done.streamId());
            } else {
                sendStreamError(slot, "Stream exceeds max bytes (" + MAX_STREAM_BYTES + "): " + done.streamId());
            }
            return;
        }
        if (isReply) {
            CompletableFuture<JsonNode> future = pending.remove(slot.replyRequestId);
            ScheduledFuture<?> t = timeouts.remove(slot.replyRequestId);
            if (t != null) {
                t.cancel(false);
            }
            if (future != null && !future.isDone()) {
                future.complete(mapper.getNodeFactory().binaryNode(assembled));
            }
            return;
        }
        StreamHandlerEntry<?, ?> entry = streamHandlers.get(slot.streamType);
        if (entry == null) {
            sendStreamError(slot, "unknown stream type: " + slot.streamType);
            return;
        }
        handlerExecutor.execute(() -> invokeStreamHandler(slot, entry, assembled));
    }

    @SuppressWarnings({ "unchecked" })
    private void invokeStreamHandler(StreamSlot slot, StreamHandlerEntry<?, ?> entry, byte[] assembled) {
        try {
            Object meta = convertStreamMeta(slot.metadata, (StreamHandlerEntry<Object, Object>) entry);
            Object result = ((StreamHandlerEntry<Object, Object>) entry).fn.apply(meta, assembled);
            sendRaw(getSession(), ackFor(slot, result));
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Stream handler failed for type=" + slot.streamType, e);
            String detail = e.getMessage() != null ? e.getMessage() : e.toString();
            sendStreamError(slot, detail);
        }
    }

    @SuppressWarnings("unchecked")
    private Object convertStreamMeta(JsonNode payload, StreamHandlerEntry<?, ?> entry) {
        Class<?> clazz = entry.metaClass;
        if (clazz == null || clazz == Void.class || clazz == Void.TYPE) {
            return null;
        }
        if (payload == null || payload.isNull() || payload.isMissingNode()) {
            return null;
        }
        if (clazz == JsonNode.class) {
            return payload;
        }
        try {
            return mapper.treeToValue(payload, (Class<Object>) clazz);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Cannot deserialize stream metadata to " + clazz.getName(), e);
        }
    }

    private WsMessage<?> ackFor(StreamSlot slot, Object result) {
        if (result instanceof WsMessage) {
            throw new IllegalArgumentException("Stream result must not be a WsMessage");
        }
        WsMessage<Object> ack = new WsMessage<>();
        ack.setId(UUID.randomUUID())
                .setType(slot.streamType)
                .setResponseOf(slot.startId)
                .setPayload(result);
        return ack;
    }

    private void sendStreamError(StreamSlot slot, String detail) {
        sendRaw(getSession(), errorFor(slot.startId, slot.streamType, detail));
    }

    private static WsMessage<?> errorFor(UUID responseOf, String type, String detail) {
        WsMessage<Object> error = new WsMessage<>();
        error.setId(UUID.randomUUID())
                .setType(type)
                .setResponseOf(responseOf)
                .setPayload(detail)
                .setError(true);
        return error;
    }

    private void cancelStreamTimeout(UUID streamId) {
        ScheduledFuture<?> t = streamTimeouts.remove(streamId);
        if (t != null) {
            t.cancel(false);
        }
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

    public synchronized void onClose(Throwable cause) {
        RuntimeException err = cause instanceof RuntimeException re ? re : new RuntimeException(cause);
        CompletableFuture<WsSession> prev = ready;
        ready = new CompletableFuture<>();
        prev.completeExceptionally(err);
        for (ScheduledFuture<?> t : streamTimeouts.values()) {
            t.cancel(false);
        }
        streamTimeouts.clear();
        streamSlots.clear();
        senderStreams.clear();
        streamReceiver.clear();

        // Clean up client subscriptions
        for (ClientSubscriptionSlot slot : clientSubscriptions.values()) {
            slot.closed = true;
            if (slot.timeoutTask != null) {
                slot.timeoutTask.cancel(false);
            }
            if (slot.ackFuture != null && !slot.ackFuture.isDone()) {
                slot.ackFuture.completeExceptionally(err);
            }
            for (Runnable cb : slot.onCloseCallbacks) {
                try {
                    cb.run();
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "Client subscription onClose callback failed", e);
                }
            }
        }
        clientSubscriptions.clear();

        // Clean up server subscriptions
        for (ServerSubscriptionSlot slot : serverSubscriptions.values()) {
            slot.closed = true;
            slot.handle.complete();
            for (Runnable cb : slot.onCloseCallbacks) {
                try {
                    cb.run();
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "Server subscription onClose callback failed", e);
                }
            }
        }
        serverSubscriptions.clear();

        if (pending.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, CompletableFuture<JsonNode>> e : pending.entrySet()) {
            UUID id = e.getKey();
            timeouts.remove(id);
            try {
                e.getValue().completeExceptionally(err);
            } catch (Exception ignored) {
            }
        }
        pending.clear();
        for (ScheduledFuture<?> t : timeouts.values()) {
            t.cancel(false);
        }
        timeouts.clear();
    }

    public void shutdown() {
        onClose(new RuntimeException("WsRpcChannel shutdown"));
        scheduler.shutdownNow();
    }

    private static final class HandlerEntry<Req, Res> {
        final Class<Req> requestClass;
        final Function<Req, Res> fn;

        HandlerEntry(Class<Req> requestClass, Function<Req, Res> fn) {
            this.requestClass = requestClass;
            this.fn = fn;
        }
    }

    /** Wire payload announcing a stream abort to the peer. */
    public record StreamAbort(
            UUID streamId,
            String reason) {
    }

    /** Wire envelope announcing a stream ahead of its binary chunks. */
    public record StreamStart(
            UUID streamId,
            String streamType,
            JsonNode metadata,
            int totalChunks,
            String sha256) {
    }

    /** Wire envelope closing a stream after its binary chunks. */
    public record StreamDone(
            UUID streamId,
            String sha256) {
    }

    /**
     * Return value for request handlers that answer with a stream instead of a
     * single frame. The requester's future resolves with {@code data}.
     */
    public record StreamReply(byte[] data, Object metadata) {
        public StreamReply {
            java.util.Objects.requireNonNull(data, "data");
        }

        public StreamReply(byte[] data) {
            this(data, null);
        }
    }

    @Data
    private static final class StreamHandlerEntry<Meta, Res> {
        final Class<Meta> metaClass;
        final Class<Res> responseType;
        final BiFunction<Meta, byte[], Res> fn;

        StreamHandlerEntry(Class<Meta> metaClass, Class<Res> responseType, BiFunction<Meta, byte[], Res> fn) {
            this.metaClass = metaClass;
            this.responseType = responseType;
            this.fn = fn;
        }
    }

    @Data
    private static final class StreamSlot {
        final UUID streamId;
        final UUID startId;
        final String streamType;
        final JsonNode metadata;
        final int totalChunks;
        final String sha256;
        final UUID replyRequestId;
        final Map<Integer, Integer> seen = new ConcurrentHashMap<>();
        final AtomicInteger receivedBytes = new AtomicInteger();
        long reservedAtNanos = System.nanoTime();

        StreamSlot(UUID streamId, UUID startId, String streamType, JsonNode metadata, int totalChunks,
                String sha256, UUID replyRequestId) {
            this.streamId = streamId;
            this.startId = startId;
            this.streamType = streamType;
            this.metadata = metadata;
            this.totalChunks = totalChunks;
            this.sha256 = sha256;
            this.replyRequestId = replyRequestId;
        }
    }

    /** Wire payload for a subscribe request. */
    public record SubscribePayload(
            String eventType,
            Object data) {
    }

    /** Client-side subscription slot. */
    @Data 
    private static final class ClientSubscriptionSlot {
        final UUID id;
        final String eventType;
        final Consumer<JsonNode> handler;
        final CompletableFuture<Void> ackFuture;
        final List<Runnable> onCloseCallbacks = new CopyOnWriteArrayList<>();
        volatile boolean closed = false;
        volatile ScheduledFuture<?> timeoutTask;

        ClientSubscriptionSlot(UUID id, String eventType, Consumer<JsonNode> handler,
                CompletableFuture<Void> ackFuture) {
            this.id = id;
            this.eventType = eventType;
            this.handler = handler;
            this.ackFuture = ackFuture;
        }
    }

    /** Server-side subscription handler entry. */
    private static final class SubscriptionHandlerEntry {
        final Class<?> paramsClass;
        final Function<SubscriptionRequest<Object>, CompletableFuture<Void>> onSubscribe;

        SubscriptionHandlerEntry(Class<?> paramsClass,
                Function<SubscriptionRequest<Object>, CompletableFuture<Void>> onSubscribe) {
            this.paramsClass = paramsClass;
            this.onSubscribe = onSubscribe;
        }
    }

    /** Server-side subscription slot. */
    @Data 
    private static final class ServerSubscriptionSlot {
        final UUID subscribeId;
        final String eventType;
        final Object params;
        final PushHandle handle;
        final List<Runnable> onCloseCallbacks = new CopyOnWriteArrayList<>();
        volatile boolean closed = false;

        ServerSubscriptionSlot(UUID subscribeId, String eventType, Object params, PushHandle handle) {
            this.subscribeId = subscribeId;
            this.eventType = eventType;
            this.params = params;
            this.handle = handle;
        }
    }

    /** Default implementation of PushHandle. */
    private final class DefaultPushHandle implements PushHandle {
        private final UUID subscribeId;
        private final String eventType;
        private final List<Runnable> closeCallbacks = new CopyOnWriteArrayList<>();
        private volatile boolean closed = false;

        DefaultPushHandle(UUID subscribeId, String eventType) {
            this.subscribeId = subscribeId;
            this.eventType = eventType;
        }

        @Override
        public void push(Object event) {
            if (closed) {
                return;
            }
            WsMessage<?> message = WsMessage.create(eventType)
                    .setResponseOf(subscribeId)
                    .withPayload(event);
            sendRaw(getSession(), message);
        }

        @Override
        public void complete() {
            if (closed) {
                return;
            }
            closed = true;
            triggerCloseCallbacks();
        }

        @Override
        public void fail(String reason) {
            if (closed) {
                return;
            }
            closed = true;
            sendRaw(getSession(), errorFor(subscribeId, eventType, reason));
            triggerCloseCallbacks();
        }

        @Override
        public boolean isClosed() {
            return closed;
        }

        @Override
        public void onClose(Runnable callback) {
            Objects.requireNonNull(callback, "callback");
            if (closed) {
                try {
                    callback.run();
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "PushHandle onClose callback failed", e);
                }
            } else {
                closeCallbacks.add(callback);
            }
        }

        private void triggerCloseCallbacks() {
            for (Runnable cb : closeCallbacks) {
                try {
                    cb.run();
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "PushHandle onClose callback failed", e);
                }
            }
        }
    }

    private Object convertSubscriptionParams(Object payload, SubscriptionHandlerEntry entry) {
        Class<?> clazz = entry.paramsClass;
        if (clazz == null || clazz == Void.class || clazz == Void.TYPE) {
            return null;
        }
        if (payload == null) {
            return null;
        }
        if (clazz == JsonNode.class) {
            return mapper.valueToTree(payload);
        }
        try {
            return mapper.convertValue(payload, clazz);
        } catch (Exception e) {
            throw new RuntimeException("Cannot deserialize subscription params to " + clazz.getName(), e);
        }
    }
}
