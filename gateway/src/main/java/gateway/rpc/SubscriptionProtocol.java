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
import com.fasterxml.jackson.databind.node.NullNode;

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

    static boolean handlesControl(String type) {
        return WsProtocol.SUBSCRIBE_TYPE.equals(type)
                || WsProtocol.UNSUBSCRIBE_TYPE.equals(type);
    }

    void handleControl(WsMessage<JsonNode> message) {
        if (WsProtocol.SUBSCRIBE_TYPE.equals(message.getType())) {
            handleSubscribe(message);
        } else if (WsProtocol.UNSUBSCRIBE_TYPE.equals(message.getType())) {
            handleUnsubscribe(message);
        }
    }

    <Params> void registerSubscriptionHandler(String eventType, Class<Params> paramsClass,
            Function<SubscriptionRequest<Params>, CompletableFuture<Void>> onSubscribe) {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(paramsClass, "paramsClass");
        Objects.requireNonNull(onSubscribe, "onSubscribe");
        WsProtocol.rejectStreamControlType(eventType);
        if (subscriptionHandlers.containsKey(eventType)) {
            throw new IllegalArgumentException("Subscription handler already registered for type: " + eventType);
        }

        Function<SubscriptionRequest<Object>, CompletableFuture<Void>> adapted = req -> onSubscribe
                .apply(new SubscriptionRequest<>(paramsClass.cast(req.params()), req.handle()));
        subscriptionHandlers.put(eventType, new SubscriptionHandlerEntry(paramsClass, adapted));
    }

    void unregisterSubscriptionHandler(String eventType) {
        subscriptionHandlers.remove(eventType);
    }

    boolean hasSubscriptionHandler(String eventType) {
        return subscriptionHandlers.containsKey(eventType);
    }

    boolean tryConsumeResponse(WsMessage<JsonNode> message) {
        UUID subscribeId = message.getResponseOf();
        ClientSubscriptionSlot slot = subscribeId == null ? null : clientSubscriptions.get(subscribeId);
        if (slot == null) {
            return false;
        }
        if (message.isError()) {
            handleSubscriptionError(message);
        } else {
            handleSubscriptionEvent(message);
        }
        return true;
    }

    CompletableFuture<Void> subscribe(UUID subscriptionId, String eventType, Object data,
            Consumer<JsonNode> handler, Duration timeout) {
        Objects.requireNonNull(subscriptionId, "subscriptionId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(handler, "handler");
        WsMessage<?> request = WsMessage.create(WsProtocol.SUBSCRIBE_TYPE)
                .setId(subscriptionId)
                .withPayload(mapper.valueToTree(new SubscribePayload(eventType, data)));
        Duration effectiveTimeout = timeout != null ? timeout : defaultTimeout;

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

        sessions.awaitOpen()
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
                    Throwable cause = err instanceof CompletionException ce
                            && ce.getCause() != null ? ce.getCause() : err;
                    ackFuture.completeExceptionally(cause);
                    return null;
                });

        return ackFuture;
    }

    void onSubscriptionClose(UUID subscriptionId, Runnable callback) {
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

    void unsubscribe(UUID requestId, String reason) {
        Objects.requireNonNull(requestId, "requestId");
        ClientSubscriptionSlot slot = clientSubscriptions.remove(requestId);
        if (slot == null) {
            return;
        }
        closeClientSubscription(slot, null);

        WsMessage<?> message = WsMessage.create(WsProtocol.UNSUBSCRIBE_TYPE)
                .withPayload(reason != null ? Map.of("reason", reason) : Map.of())
                .setResponseOf(requestId);

        sessions.awaitOpen()
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

    private void handleSubscribe(WsMessage<JsonNode> message) {
        UUID subscribeId = message.getId();
        final SubscribePayload payload;
        try {
            payload = mapper.treeToValue(message.getPayload(), SubscribePayload.class);
        } catch (JsonProcessingException e) {
            LOG.log(Level.WARNING, "Dropping malformed subscribe frame: " + e.getMessage(), e);
            sink.send(Frames.errorFor(message.getId(), WsProtocol.SUBSCRIBE_TYPE,
                    "Malformed subscribe frame: " + e.getMessage()));
            return;
        }
        if (payload == null || payload.eventType() == null) {
            LOG.warning("Dropping subscribe with missing eventType");
            sink.send(Frames.errorFor(message.getId(), WsProtocol.SUBSCRIBE_TYPE,
                    "Subscribe is missing required field: eventType"));
            return;
        }

        SubscriptionHandlerEntry entry = subscriptionHandlers.get(payload.eventType());
        if (entry == null) {
            LOG.info("No subscription handler for type: " + payload.eventType());
            sink.send(Frames.errorFor(message.getId(), payload.eventType(),
                    "unknown subscription type: " + payload.eventType()));
            return;
        }

        Object params;
        try {
            params = convertSubscriptionParams(payload.data(), entry);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to deserialize subscription params", e);
            sink.send(Frames.errorFor(message.getId(), payload.eventType(),
                    "Invalid subscription parameters: " + e.getMessage()));
            return;
        }

        DefaultPushHandle handle = new DefaultPushHandle(subscribeId, payload.eventType(), sink);
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
            sink.send(WsMessage.create(payload.eventType())
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
            sink.send(WsMessage.create(payload.eventType())
                    .setResponseOf(subscribeId)
                    .withPayload(NullNode.getInstance()));
        });
    }

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
        closeServerSubscription(slot);
    }

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

    private void handleSubscriptionError(WsMessage<JsonNode> message) {
        UUID subscribeId = message.getResponseOf();
        if (subscribeId == null) {
            return;
        }
        ClientSubscriptionSlot slot = clientSubscriptions.remove(subscribeId);
        if (slot == null) {
            return;
        }
        String detail = message.getPayload() == null ? "subscription failed"
                : message.getPayload().isTextual() ? message.getPayload().asText()
                        : message.getPayload().toString();
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
                LOG.log(Level.WARNING, "Subscription onClose callback failed", e);
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
                LOG.log(Level.WARNING, "Subscription onClose callback failed", e);
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
            throw new RuntimeException("Cannot deserialize subscription params to " + clazz.getName(), e);
        }
    }
}
