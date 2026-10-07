package gateway.subscription;


import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import gateway.rpc.FrameWriter;
import gateway.rpc.InboundFrame;
import gateway.session.SessionProvider;
import gateway.session.WsSession;
import gateway.util.FrameFactory;
import gateway.wire.NoSessionException;
import gateway.wire.SubscriptionPayload;
import gateway.wire.WsMessage;
import gateway.wire.WsProtocol;

public final class SubscriptionProtocol {

    private static final Logger LOG = Logger.getLogger(SubscriptionProtocol.class.getName());

    private final ObjectMapper mapper;
    private final ScheduledExecutorService scheduler;
    private final Duration defaultTimeout;
    private final FrameWriter sink;
    private final SessionProvider sessions;

    private final Map<UUID, ClientSubscription> clientSubscriptions = new ConcurrentHashMap<>();
    private final Map<String, RegisteredSubscription> subscriptionHandlers = new ConcurrentHashMap<>();
    private final Map<UUID, ServerSubscription> serverSubscriptions = new ConcurrentHashMap<>();

    public SubscriptionProtocol(ObjectMapper mapper, ScheduledExecutorService scheduler, Duration defaultTimeout,
            FrameWriter sink, SessionProvider sessions) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.defaultTimeout = Objects.requireNonNull(defaultTimeout, "defaultTimeout");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
    }

    public void handleControl(InboundFrame<WsMessage<JsonNode>> ctx) {
        WsMessage<JsonNode> message = ctx.body();
        if (WsProtocol.SUBSCRIBE_TYPE.equals(message.getKind())) {
            handleListen(ctx);
        } else if (WsProtocol.UNSUBSCRIBE_TYPE.equals(message.getKind())) {
            handleUnlisten(ctx);
        }
    }

    public void handleClientFrame(InboundFrame<WsMessage<JsonNode>> ctx) {
        WsMessage<JsonNode> message = ctx.body();
        String kind = message.getKind();
        if (WsProtocol.SUBSCRIBED_TYPE.equals(kind)) {
            handleListening(message);
        } else if (WsProtocol.EVENT_TYPE.equals(kind)) {
            handleEvent(message);
        } else if (WsProtocol.SUBSCRIPTION_ENDED_TYPE.equals(kind)) {
            handleListenEnded(message);
        } else if (WsProtocol.SUBSCRIPTION_ERROR_TYPE.equals(kind)) {
            handleListenError(message);
        } else {
            LOG.fine("Dropping event-stream frame with unknown kind: " + kind);
        }
    }

    public <Params> void registerEventListener(String event, Class<Params> paramsClass,
            Function<SubscriptionRequest<Params>, CompletableFuture<Void>> onListen) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(paramsClass, "paramsClass");
        Objects.requireNonNull(onListen, "onListen");
        if (subscriptionHandlers.containsKey(event)) {
            throw new IllegalArgumentException("Event listener already registered for event: " + event);
        }

        Function<SubscriptionRequest<Object>, CompletableFuture<Void>> adapted = req -> onListen
                .apply(new SubscriptionRequest<>(paramsClass.cast(req.params()), req.handle()));
        subscriptionHandlers.put(event, new RegisteredSubscription(paramsClass, adapted));
    }

    public void unregisterEventListener(String event) {
        subscriptionHandlers.remove(event);
    }

    public boolean hasEventListener(String event) {
        return subscriptionHandlers.containsKey(event);
    }

    public CompletableFuture<Void> subscribe(UUID listenId, String event, Object data,
            Consumer<JsonNode> handler, Duration timeout) {
        Objects.requireNonNull(listenId, "listenId");
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(handler, "handler");
        WsSession session = sessions.current();
        if (session == null || !session.isOpen()) {
            return CompletableFuture.failedFuture(new NoSessionException(event));
        }
        WsMessage<?> request = WsMessage.create(WsProtocol.SUBSCRIBE_TYPE)
                .setId(listenId)
                .setEvent(event)
                .withPayload(mapper.valueToTree(new SubscriptionPayload(data)));
        Duration effectiveTimeout = timeout != null ? timeout : defaultTimeout;

        CompletableFuture<Void> ackFuture = new CompletableFuture<>();
        ClientSubscription slot = new ClientSubscription(listenId, event, handler, ackFuture);
        clientSubscriptions.put(listenId, slot);

        ScheduledFuture<?> timeoutTask = scheduler.schedule(() -> {
            ClientSubscription removed = clientSubscriptions.remove(listenId);
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

        try {
            session.sendText(mapper.writeValueAsString(request));
        } catch (Exception e) {
            clientSubscriptions.remove(listenId);
            ackFuture.completeExceptionally(e);
        }

        return ackFuture;
    }

    public void onSubscriptionClose(UUID listenId, Runnable callback) {
        Objects.requireNonNull(listenId, "listenId");
        Objects.requireNonNull(callback, "callback");
        ClientSubscription slot = clientSubscriptions.get(listenId);
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

    public void unsubscribe(UUID listenId, String reason) {
        Objects.requireNonNull(listenId, "listenId");
        ClientSubscription slot = clientSubscriptions.remove(listenId);
        if (slot == null) {
            return;
        }
        closeClientSubscription(slot, null);

        WsMessage<?> message = WsMessage.create(WsProtocol.UNSUBSCRIBE_TYPE)
                .setEvent(slot.eventType)
                .setResponseOf(listenId)
                .withPayload(reason != null ? Map.of("reason", reason) : Map.of());

        WsSession session = sessions.current();
        if (session == null || !session.isOpen()) {
            LOG.info("Dropping unlisten, no open session for event=" + slot.eventType);
            return;
        }
        try {
            session.sendText(mapper.writeValueAsString(message));
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to send unlisten", e);
        }
    }

    private void handleListen(InboundFrame<WsMessage<JsonNode>> ctx) {
        WsMessage<JsonNode> message = ctx.body();
        UUID listenId = message.getId();
        String event = message.getEvent();
        if (event == null) {
            LOG.warning("Dropping listen with missing event");
            return;
        }
        final SubscriptionPayload listen;
        try {
            JsonNode payload = message.getPayload();
            listen = payload == null ? new SubscriptionPayload(null)
                    : mapper.treeToValue(payload, SubscriptionPayload.class);
        } catch (JsonProcessingException e) {
            LOG.log(Level.WARNING, "Dropping malformed listen frame: " + e.getMessage(), e);
            sink.send(ctx.origin(), FrameFactory.subscriptionError(event, listenId,
                    "Malformed listen frame: " + e.getMessage()));
            return;
        }
        if (listen == null) {
            LOG.warning("Dropping listen with missing payload");
            sink.send(ctx.origin(), FrameFactory.subscriptionError(event, listenId,
                    "Listen is missing required payload"));
            return;
        }

        RegisteredSubscription entry = subscriptionHandlers.get(event);
        if (entry == null) {
            LOG.info("No event listener for: " + event);
            sink.send(ctx.origin(), FrameFactory.subscriptionError(event, listenId,
                    "unknown event: " + event));
            return;
        }

        Object params;
        try {
            params = convertSubscriptionParams(listen.data(), entry);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to deserialize listen params", e);
            sink.send(ctx.origin(), FrameFactory.subscriptionError(event, listenId,
                    "Invalid listen parameters: " + e.getMessage()));
            return;
        }

        SessionPushHandle handle = new SessionPushHandle(listenId, event, ctx.origin(), sink);
        ServerSubscription slot = new ServerSubscription(
                listenId, event, handle, ctx.origin());
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
            sink.send(ctx.origin(), FrameFactory.subscribedAck(event, listenId));
            return;
        }

        future.whenComplete((v, err) -> {
            if (err != null) {
                serverSubscriptions.remove(listenId);
                String detail = err.getMessage() != null ? err.getMessage() : err.toString();
                handle.fail(detail);
                return;
            }
            sink.send(ctx.origin(), FrameFactory.subscribedAck(event, listenId));
        });
    }

    private void handleUnlisten(InboundFrame<WsMessage<JsonNode>> ctx) {
        WsMessage<JsonNode> message = ctx.body();
        UUID listenId = message.getResponseOf();
        if (listenId == null) {
            LOG.fine("Dropping unlisten with missing responseOf");
            return;
        }
        ServerSubscription slot = serverSubscriptions.remove(listenId);
        if (slot == null) {
            LOG.fine("Dropping unlisten for unknown event stream: " + listenId);
            return;
        }
        closeServerSubscription(slot);
    }

    private void handleListening(WsMessage<JsonNode> message) {
        UUID listenId = message.getResponseOf();
        if (listenId == null) {
            LOG.fine("Dropping listening without responseOf");
            return;
        }
        ClientSubscription slot = clientSubscriptions.get(listenId);
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
        ClientSubscription slot = clientSubscriptions.get(listenId);
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
        ClientSubscription slot = clientSubscriptions.remove(listenId);
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
        ClientSubscription slot = clientSubscriptions.remove(listenId);
        if (slot == null) {
            return;
        }
        JsonNode payload = message.getPayload();
        String detail = payload == null ? "event stream failed"
                : payload.isTextual() ? payload.asText()
                        : payload.toString();
        closeClientSubscription(slot, new RuntimeException(detail));
    }

    private void closeClientSubscription(ClientSubscription slot, Throwable ackError) {
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

    private void closeServerSubscription(ServerSubscription slot) {
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

    public void teardownClient(RuntimeException err) {
        for (ClientSubscription slot : clientSubscriptions.values()) {
            closeClientSubscription(slot, err);
        }
        clientSubscriptions.clear();
    }

    public void teardownServer() {
        for (ServerSubscription slot : serverSubscriptions.values()) {
            closeServerSubscription(slot);
        }
        serverSubscriptions.clear();
    }

    private Object convertSubscriptionParams(Object payload, RegisteredSubscription entry) {
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
