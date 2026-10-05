package gateway.rpc;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import gateway.WsMessage;
import gateway.session.WsSession;

/**
 * Transport-agnostic multiplexed RPC channel.
 *
 * <p>Matches async request {@code id} values with {@code responseOf} values via
 * {@link CompletableFuture}, dispatches incoming requests to typed handlers,
 * enforces per-request timeouts, and fails pending futures on close.
 */
public class WsRpcChannel {

    private static final Logger LOG = Logger.getLogger(WsRpcChannel.class.getName());
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(1);
    private static final TypeReference<WsMessage<JsonNode>> MESSAGE_TYPE =
            new TypeReference<WsMessage<JsonNode>>() {};

    private final ObjectMapper mapper;
    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;
    private final Executor handlerExecutor;

    private final Map<UUID, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final Map<UUID, ScheduledFuture<?>> timeouts = new ConcurrentHashMap<>();
    private final Map<String, HandlerEntry<?, ?>> handlers = new ConcurrentHashMap<>();

    private volatile WsSession session;

    public WsRpcChannel() {
        this(defaultMapper(), defaultScheduler(), Runnable::run);
    }

    public WsRpcChannel(WsSession session) {
        this(defaultMapper(), defaultScheduler(), Runnable::run);
        this.session = session;
    }

    public WsRpcChannel(ObjectMapper mapper, ScheduledExecutorService scheduler, Executor handlerExecutor) {
        this.mapper = mapper != null ? mapper : defaultMapper();
        if (scheduler != null) {
            this.scheduler = scheduler;
            this.ownsScheduler = false;
        } else {
            this.scheduler = defaultScheduler();
            this.ownsScheduler = true;
        }
        this.handlerExecutor = handlerExecutor != null ? handlerExecutor : Runnable::run;
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

    public void setSession(WsSession session) {
        this.session = session;
    }

    public WsSession getSession() {
        return session;
    }

    public ObjectMapper getObjectMapper() {
        return mapper;
    }

    // ------------------------------------------------------------------
    // Handler registration
    // ------------------------------------------------------------------

    public <Req, Res> void registerHandler(String type, Class<Req> requestClass, Function<Req, Res> handler) {
        if (type == null || handler == null) {
            throw new IllegalArgumentException("type and handler must not be null");
        }
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

    // ------------------------------------------------------------------
    // Outbound requests
    // ------------------------------------------------------------------

    public CompletableFuture<Void> sendRequest(String type, Object payload) {
        return sendRequest(type, payload, Void.class, DEFAULT_TIMEOUT);
    }

    public <Res> CompletableFuture<Res> sendRequest(String type, Object payload, Class<Res> responseType) {
        return sendRequest(type, payload, responseType, DEFAULT_TIMEOUT);
    }

    public <Res> CompletableFuture<Res> sendRequest(String type, Object payload, Class<Res> responseType, Duration timeout) {
        WsMessage<?> request = WsMessage.create(type).withPayload(payload);
        UUID id = request.getId();
        Duration effectiveTimeout = timeout != null ? timeout : DEFAULT_TIMEOUT;

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

        WsSession s = this.session;
        if (s == null || !s.isOpen()) {
            failPending(id, new IllegalStateException("No open WebSocket session for RPC request: " + type));
        } else {
            try {
                String json = mapper.writeValueAsString(request);
                s.sendText(json);
            } catch (JsonProcessingException e) {
                failPending(id, e);
            } catch (RuntimeException e) {
                failPending(id, e);
            }
        }

        return raw.thenApply(node -> convert(node, responseType));
    }

    /**
     * Fire-and-forget notification (no {@code id} correlation expected back).
     * Sends a {@link WsMessage} without tracking a pending future.
     */
    public void sendNotification(String type, Object payload) {
        WsSession s = this.session;
        if (s == null || !s.isOpen()) {
            LOG.fine("Dropping notification, no open session: " + type);
            return;
        }
        try {
            WsMessage<?> message = WsMessage.create(type).withPayload(payload);
            s.sendText(mapper.writeValueAsString(message));
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to send notification: " + type, e);
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
        if (responseType == String.class && node.isTextual()) {
            return (Res) node.asText();
        }
        try {
            return mapper.treeToValue(node, responseType);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Cannot convert RPC payload to " + responseType.getName() + ": " + e.getMessage(), e);
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

    // ------------------------------------------------------------------
    // Inbound dispatch
    // ------------------------------------------------------------------

    /**
     * Parse one text frame and route it: either complete a pending request
     * (when {@code responseOf} is set) or execute a registered handler and
     * send back a response / error frame.
     */
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

        if (message.getResponseOf() != null) {
            CompletableFuture<JsonNode> future = pending.remove(message.getResponseOf());
            if (future == null) {
                LOG.fine("No pending RPC for responseOf: " + message.getResponseOf());
                return;
            }
            ScheduledFuture<?> t = timeouts.remove(message.getResponseOf());
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
            LOG.fine("Dropping RPC frame without type");
            return;
        }
        HandlerEntry<?, ?> entry = handlers.get(type);
        if (entry == null) {
            LOG.fine("No RPC handler for type: " + type);
            return;
        }

        handlerExecutor.execute(() -> invokeHandler(message, entry));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void invokeHandler(WsMessage<JsonNode> message, HandlerEntry<?, ?> entry) {
        WsSession s = this.session;
        try {
            Object param = convertParam(message.getPayload(), (HandlerEntry<Object, Object>) entry);
            Object result = ((HandlerEntry<Object, Object>) entry).fn.apply(param);
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

    private void sendRaw(WsSession s, WsMessage<?> message) {
        if (s == null || !s.isOpen()) {
            LOG.fine("Dropping RPC response, no open session for type=" + message.getType());
            return;
        }
        try {
            s.sendText(mapper.writeValueAsString(message));
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to send RPC frame", e);
        }
    }

    /**
     * Fail all pending futures; called when the transport closes so callers
     * never hang forever.
     */
    public void onClose() {
        onClose(new RuntimeException("WebSocket connection closed"));
    }

    public void onClose(Throwable cause) {
        if (pending.isEmpty()) {
            return;
        }
        RuntimeException err = cause instanceof RuntimeException re ? re : new RuntimeException(cause);
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

    /** Release the internal scheduler when this channel owns it. */
    public void shutdown() {
        onClose(new RuntimeException("WsRpcChannel shutdown"));
        if (ownsScheduler) {
            scheduler.shutdownNow();
        }
    }

    private static final class HandlerEntry<Req, Res> {
        final Class<Req> requestClass;
        final Function<Req, Res> fn;

        HandlerEntry(Class<Req> requestClass, Function<Req, Res> fn) {
            this.requestClass = requestClass;
            this.fn = fn;
        }
    }
}
