package gateway.rpc;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import gateway.WsMessage;

final class SubscriptionProtocol {

    private static final Logger LOG = Logger.getLogger(SubscriptionProtocol.class.getName());

    private final ObjectMapper mapper;
    private final ScheduledExecutorService scheduler;
    private final Duration defaultTimeout;
    private final FrameSink sink;
    private final SessionGate sessions;

    private final Map<UUID, ClientSubscriptionSlot> clientSubscriptions = new ConcurrentHashMap<>();
    private final Map<String, SubscriptionHandlerEntry> subscriptionHandlers = new ConcurrentHashMap<>();
    private final Map<UUID, ServerSubscriptionSlot> serverSubscriptions = new ConcurrentHashMap<>();

    SubscriptionProtocol(ObjectMapper mapper, ScheduledExecutorService scheduler, Duration defaultTimeout,
            FrameSink sink, SessionGate sessions) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.defaultTimeout = Objects.requireNonNull(defaultTimeout, "defaultTimeout");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
    }

    void handleControl(FrameContext<WsMessage<JsonNode>> ctx) {
        WsMessage<JsonNode> message = ctx.body();
        if (WsProtocol.LISTEN_TYPE.equals(message.getKind())) {
            handleListen(ctx);
        } else if (WsProtocol.UNLISTEN_TYPE.equals(message.getKind())) {
            handleUnlisten(ctx);
        }
    }

    void handleClientFrame(FrameContext<WsMessage<JsonNode>> ctx) {
        WsMessage<JsonNode> message = ctx.body();
        String kind = message.getKind();
        if (WsProtocol.LISTENING_TYPE.equals(kind)) {
            handleListening(message);
        } else if (WsProtocol.EVENT_TYPE.equals(kind)) {
            handleEvent(message);
        } else if (WsProtocol.LISTEN_ENDED_TYPE.equals(kind)) {
            handleListenEnded(message);
        } else if (WsProtocol.LISTEN_ERROR_TYPE.equals(kind)) {
            handleListenError(message);
        } else {
            LOG.fine("Dropping event-stream frame with unknown kind: " + kind);
        }
    }

    <Params> void registerEventListener(String event, Class<Params> paramsClass,
            Function<SubscriptionRequest<Params>, CompletableFuture<Void>> onListen) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(paramsClass, "paramsClass");
        Objects.requireNonNull(onListen, "onListen");
        if (subscriptionHandlers.containsKey(event)) {
            throw new IllegalArgumentException("Event listener already registered for event: " + event);
        }

        Function<SubscriptionRequest<Object>, CompletableFuture<Void>> adapted = req -> onListen
                .apply(new SubscriptionRequest<>(paramsClass.cast(req.params()), req.handle()));
        subscriptionHandlers.put(event, new SubscriptionHandlerEntry(paramsClass, adapted));
    }

    void unregisterEventListener(String event) {
        subscriptionHandlers.remove(event);
    }

    boolean hasEventListener(String event) {
        return subscriptionHandlers.containsKey(event);
    }

    CompletableFuture<Void> listen(UUID listenId, String event, Object data,
            Consumer<JsonNode> handler, Duration timeout) {
        Objects.requireNonNull(listenId, "listenId");
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(handler, "handler");
        WsMessage<?> request = WsMessage.create(WsProtocol.LISTEN_TYPE)
                .setId(listenId)
                .setEvent(event)
                .withPayload(mapper.valueToTree(new ListenPayload(data)));
        Duration effectiveTimeout = timeout != null ? timeout : defaultTimeout;

        CompletableFuture<Void> ackFuture = new CompletableFuture<>();
        ClientSubscriptionSlot slot = new ClientSubscriptionSlot(listenId, event, handler, ackFuture);
        clientSubscriptions.put(listenId, slot);

        ScheduledFuture<?> timeoutTask = scheduler.schedule(() -> {
            ClientSubscriptionSlot removed = clientSubscriptions.remove(listenId);
            if (removed != null && !removed.ackFuture.isDone()) {
                removed.closed = true;
                removed.ackFuture.completeExceptionally(
                        new TimeoutException("Event stream timed out: event=" + event + " id=" + listenId));
            }
        }, effectiveTimeout.toMillis(), TimeUnit.MILLISECONDS);
        slot.timeoutTask = timeoutTask;

        ackFuture.whenComplete((res, err) -> {
            ScheduledFuture<?> t = slot.timeoutTask;
            if (t != null) {
                t.cancel(false);
            }
        });

        sessions.awaitOpen()
                .thenAccept(s -> {
                    if (!s.isOpen()) {
                        clientSubscriptions.remove(listenId);
                        ackFuture.completeExceptionally(
                                new IllegalStateException("No open WebSocket session for listen: " + event));
                        return;
                    }
                    try {
                        s.sendText(mapper.writeValueAsString(request));
                    } catch (Exception e) {
                        clientSubscriptions.remove(listenId);
                        ackFuture.completeExceptionally(e);
                    }
                })
                .exceptionally(err -> {
                    clientSubscriptions.remove(listenId);
                    Throwable cause = err instanceof CompletionException ce
                            && ce.getCause() != null ? ce.getCause() : err;
                    ackFuture.completeExceptionally(cause);
                    return null;
                });

        return ackFuture;
    }

    void onListenClose(UUID listenId, Runnable callback) {
        Objects.requireNonNull(listenId, "listenId");
        Objects.requireNonNull(callback, "callback");
        ClientSubscriptionSlot slot = clientSubscriptions.get(listenId);
        if (slot == null || slot.closed) {
            try {
                callback.run();
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Event stream onClose callback failed", e);
            }
        } else {
            slot.onCloseCallbacks.add(callback);
        }
    }

    void unlisten(UUID listenId, String reason) {
        Objects.requireNonNull(listenId, "listenId");
        ClientSubscriptionSlot slot = clientSubscriptions.remove(listenId);
        if (slot == null) {
            return;
        }
        closeClientSubscription(slot, null);

        WsMessage<?> message = WsMessage.create(WsProtocol.UNLISTEN_TYPE)
                .setEvent(slot.eventType)
                .setResponseOf(listenId)
                .withPayload(reason != null ? Map.of("reason", reason) : Map.of());

        sessions.awaitOpen()
                .thenAccept(s -> {
                    if (!s.isOpen()) {
                        return;
                    }
                    try {
                        s.sendText(mapper.writeValueAsString(message));
                    } catch (Exception e) {
                        LOG.log(Level.WARNING, "Failed to send unlisten", e);
                    }
                })
                .exceptionally(err -> null);
    }

    private void handleListen(FrameContext<WsMessage<JsonNode>> ctx) {
        WsMessage<JsonNode> message = ctx.body();
        UUID listenId = message.getId();
        String event = message.getEvent();
        if (event == null) {
            LOG.warning("Dropping listen with missing event");
            return;
        }
        final ListenPayload listen;
        try {
            JsonNode payload = message.getPayload();
            listen = payload == null ? new ListenPayload(null)
                    : mapper.treeToValue(payload, ListenPayload.class);
        } catch (JsonProcessingException e) {
            LOG.log(Level.WARNING, "Dropping malformed listen frame: " + e.getMessage(), e);
            sink.send(ctx.origin(), Frames.listenError(event, listenId,
                    "Malformed listen frame: " + e.getMessage()));
            return;
        }
        if (listen == null) {
            LOG.warning("Dropping listen with missing payload");
            sink.send(ctx.origin(), Frames.listenError(event, listenId,
                    "Listen is missing required payload"));
            return;
        }

        SubscriptionHandlerEntry entry = subscriptionHandlers.get(event);
        if (entry == null) {
            LOG.info("No event listener for: " + event);
            sink.send(ctx.origin(), Frames.listenError(event, listenId,
                    "unknown event: " + event));
            return;
        }

        Object params;
        try {
            params = convertSubscriptionParams(listen.data(), entry);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to deserialize listen params", e);
            sink.send(ctx.origin(), Frames.listenError(event, listenId,
                    "Invalid listen parameters: " + e.getMessage()));
            return;
        }

        DefaultPushHandle handle = new DefaultPushHandle(listenId, event, ctx.origin(), sink);
        ServerSubscriptionSlot slot = new ServerSubscriptionSlot(
                listenId, event, params, handle, ctx.origin());
        if (serverSubscriptions.putIfAbsent(listenId, slot) != null) {
            handle.fail("Duplicate event stream ID");
            return;
        }

        handle.onClose(() -> serverSubscriptions.remove(listenId));

        CompletableFuture<Void> future;
        try {
            future = entry.onSubscribe.apply(new SubscriptionRequest<>(params, handle));
        } catch (Exception e) {
            LOG.log(Level.WARNING, "onListen threw exception", e);
            serverSubscriptions.remove(listenId);
            handle.fail("Event listener failed: " + e.getMessage());
            return;
        }

        if (future == null) {
            sink.send(ctx.origin(), Frames.listeningAck(event, listenId));
            return;
        }

        future.whenComplete((v, err) -> {
            if (err != null) {
                serverSubscriptions.remove(listenId);
                String detail = err.getMessage() != null ? err.getMessage() : err.toString();
                handle.fail(detail);
                return;
            }
            sink.send(ctx.origin(), Frames.listeningAck(event, listenId));
        });
    }

    private void handleUnlisten(FrameContext<WsMessage<JsonNode>> ctx) {
        WsMessage<JsonNode> message = ctx.body();
        UUID listenId = message.getResponseOf();
        if (listenId == null) {
            LOG.fine("Dropping unlisten with missing responseOf");
            return;
        }
        ServerSubscriptionSlot slot = serverSubscriptions.remove(listenId);
        if (slot == null) {
            LOG.fine("Dropping unlisten for unknown event stream: " + listenId);
            return;
        }
        sink.send(slot.origin, Frames.listenEnded(slot.eventType, listenId));
        closeServerSubscription(slot);
    }

    private void handleListening(WsMessage<JsonNode> message) {
        UUID listenId = message.getResponseOf();
        if (listenId == null) {
            LOG.fine("Dropping listening without responseOf");
            return;
        }
        ClientSubscriptionSlot slot = clientSubscriptions.get(listenId);
        if (slot == null) {
            LOG.fine("Dropping listening for unknown event stream: " + listenId);
            return;
        }
        if (slot.closed) {
            LOG.fine("Dropping listening for closed event stream: " + listenId);
            return;
        }
        if (slot.ackFuture != null && !slot.ackFuture.isDone()) {
            slot.ackFuture.complete(null);
        }
    }

    private void handleEvent(WsMessage<JsonNode> message) {
        UUID listenId = message.getResponseOf();
        if (listenId == null) {
            LOG.fine("Dropping event frame without responseOf");
            return;
        }
        ClientSubscriptionSlot slot = clientSubscriptions.get(listenId);
        if (slot == null) {
            LOG.fine("Dropping event for unknown event stream: " + listenId);
            return;
        }
        if (slot.closed) {
            LOG.fine("Dropping event for closed event stream: " + listenId);
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
                LOG.log(Level.WARNING, "Event handler failed for " + slot.eventType, e);
            }
        }
    }

    private void handleListenEnded(WsMessage<JsonNode> message) {
        UUID listenId = message.getResponseOf();
        if (listenId == null) {
            return;
        }
        ClientSubscriptionSlot slot = clientSubscriptions.remove(listenId);
        if (slot == null) {
            return;
        }
        closeClientSubscription(slot, null);
    }

    private void handleListenError(WsMessage<JsonNode> message) {
        UUID listenId = message.getResponseOf();
        if (listenId == null) {
            return;
        }
        ClientSubscriptionSlot slot = clientSubscriptions.remove(listenId);
        if (slot == null) {
            return;
        }
        JsonNode payload = message.getPayload();
        String detail = payload == null ? "event stream failed"
                : payload.isTextual() ? payload.asText()
                        : payload.toString();
        closeClientSubscription(slot, new RuntimeException(detail));
    }

    private void closeClientSubscription(ClientSubscriptionSlot slot, Throwable ackError) {
        slot.closed = true;
        if (slot.timeoutTask != null) {
            slot.timeoutTask.cancel(false);
        }
        if (ackError != null && slot.ackFuture != null && !slot.ackFuture.isDone()) {
            slot.ackFuture.completeExceptionally(ackError);
        }
        for (Runnable cb : slot.onCloseCallbacks) {
            try {
                cb.run();
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Event stream onClose callback failed", e);
            }
        }
    }

    private void closeServerSubscription(ServerSubscriptionSlot slot) {
        slot.closed = true;
        slot.handle.complete();
        for (Runnable cb : slot.onCloseCallbacks) {
            try {
                cb.run();
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Event stream onClose callback failed", e);
            }
        }
    }

    void teardownClient(RuntimeException err) {
        for (ClientSubscriptionSlot slot : clientSubscriptions.values()) {
            closeClientSubscription(slot, err);
        }
        clientSubscriptions.clear();
    }

    void teardownServer() {
        for (ServerSubscriptionSlot slot : serverSubscriptions.values()) {
            closeServerSubscription(slot);
        }
        serverSubscriptions.clear();
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
            throw new RuntimeException("Cannot deserialize listen params to " + clazz.getName(), e);
        }
    }
}
