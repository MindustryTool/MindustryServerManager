package gateway.rpc;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;

import gateway.WsMessage;
import gateway.session.WsSession;
import gateway.stream.FileChunkStreamer;

final class RpcProtocol implements ReplySink {

    private static final Logger LOG = Logger.getLogger(RpcProtocol.class.getName());

    private final ObjectMapper mapper;
    private final ScheduledExecutorService scheduler;
    private final Executor handlerExecutor;
    private final Duration defaultTimeout;
    private final FrameSink sink;
    private final SessionGate sessions;

    private final Map<UUID, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final Map<UUID, ScheduledFuture<?>> timeouts = new ConcurrentHashMap<>();
    private final Map<String, HandlerEntry<?, ?>> handlers = new ConcurrentHashMap<>();

    RpcProtocol(ObjectMapper mapper, ScheduledExecutorService scheduler, Executor handlerExecutor,
            Duration defaultTimeout, FrameSink sink, SessionGate sessions) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.handlerExecutor = Objects.requireNonNull(handlerExecutor, "handlerExecutor");
        this.defaultTimeout = Objects.requireNonNull(defaultTimeout, "defaultTimeout");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
    }

    <Req, Res> void registerHandler(String type, Class<Req> requestClass, Function<Req, Res> handler) {
        if (type == null || handler == null) {
            throw new IllegalArgumentException("type and handler must not be null");
        }
        handlers.put(type, new HandlerEntry<>(requestClass, handler));
    }

    void unregisterHandler(String type) {
        handlers.remove(type);
    }

    boolean hasHandler(String type) {
        return handlers.containsKey(type);
    }

    int pendingCount() {
        return pending.size();
    }

    boolean hasPending(UUID id) {
        return pending.containsKey(id);
    }

    CompletableFuture<JsonNode> takePending(UUID id) {
        CompletableFuture<JsonNode> future = pending.remove(id);
        ScheduledFuture<?> timeout = timeouts.remove(id);
        if (timeout != null) {
            timeout.cancel(false);
        }
        return future;
    }

    void failPending(UUID id, Throwable err) {
        CompletableFuture<JsonNode> future = takePending(id);
        if (future != null && !future.isDone()) {
            future.completeExceptionally(err);
        }
    }

    @Override
    public void failReply(UUID replyRequestId, String detail) {
        failPending(replyRequestId, new RuntimeException(detail));
    }

    CompletableFuture<JsonNode> registerPending(UUID id, Duration timeout, String timeoutDetail) {
        CompletableFuture<JsonNode> raw = new CompletableFuture<>();
        pending.put(id, raw);
        ScheduledFuture<?> timeoutTask = scheduler.schedule(() -> failPending(id, new TimeoutException(timeoutDetail)),
                timeout.toMillis(), TimeUnit.MILLISECONDS);
        timeouts.put(id, timeoutTask);
        raw.whenComplete((res, err) -> {
            ScheduledFuture<?> t = timeouts.remove(id);
            if (t != null) {
                t.cancel(false);
            }
            pending.remove(id);
        });
        return raw;
    }

    void teardownPending(RuntimeException err) {
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

    <Res> CompletableFuture<Res> sendRequest(String type, Object payload, Class<Res> responseType,
            Duration timeout) {
        WsMessage<?> request = WsMessage.create(WsProtocol.REQUEST_TYPE).setType(type).withPayload(payload);
        UUID id = request.getId();
        Duration effectiveTimeout = timeout != null ? timeout : defaultTimeout;

        CompletableFuture<JsonNode> sent = sessions.awaitOpen()
                .thenCompose(s -> transmit(id, request, effectiveTimeout, type, s));

        return sent.thenApply(node -> convert(node, responseType));
    }

    CompletableFuture<JsonNode> transmit(UUID id, WsMessage<?> request, Duration effectiveTimeout,
            String type, WsSession session) {
        CompletableFuture<JsonNode> raw = registerPending(id, effectiveTimeout,
                "RPC request timed out: type=" + type + " id=" + id);

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

    void sendNotification(String type, Object payload) {
        WsMessage<?> message;
        try {
            message = WsMessage.create(WsProtocol.NOTIFICATION_TYPE).setType(type).withPayload(payload);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to queue notification: " + type, e);
            return;
        }
        sessions.awaitOpen()
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
                    Throwable cause = err instanceof CompletionException ce
                            && ce.getCause() != null ? ce.getCause() : err;
                    if (cause instanceof TimeoutException) {
                        LOG.fine("Dropping notification (session wait timed out): " + type);
                    } else {
                        LOG.info("Dropping notification (connection closed): " + type);
                    }
                    return null;
                });
    }

    void settleResponse(FrameContext<WsMessage<JsonNode>> ctx) {
        WsMessage<JsonNode> message = ctx.body();
        UUID responseOf = message.getResponseOf();
        if (responseOf == null) {
            LOG.fine("Dropping RPC answer without responseOf, kind: " + message.getKind());
            return;
        }
        CompletableFuture<JsonNode> future = takePending(responseOf);
        if (future == null) {
            LOG.fine("No pending RPC for responseOf: " + responseOf + " kind: " + message.getKind());
            return;
        }
        if (WsProtocol.RESPONSE_ERROR_TYPE.equals(message.getKind())) {
            JsonNode payload = message.getPayload();
            String detail = payload == null ? "remote error" : payload.toString();
            future.completeExceptionally(new RuntimeException(detail));
        } else {
            future.complete(message.getPayload());
        }
    }

    void dispatchRequest(FrameContext<WsMessage<JsonNode>> ctx) {
        WsMessage<JsonNode> message = ctx.body();
        String type = message.getType();
        HandlerEntry<?, ?> entry = handlers.get(type);
        if (entry == null) {
            LOG.info("No RPC handler for type: " + type);
            sink.send(ctx.origin(), Frames.responseError(message.getId(), type, "unknown RPC type: " + type));
            return;
        }

        handlerExecutor.execute(() -> invokeHandler(ctx, entry));
    }

    /**
     * Dispatch a notification. Notifications declare that no answer is
     * expected, so unknown types are dropped with a log and handler
     * failures are logged without any reply.
     */
    void dispatchNotification(FrameContext<WsMessage<JsonNode>> ctx) {
        String type = ctx.body().getType();
        HandlerEntry<?, ?> entry = handlers.get(type);
        if (entry == null) {
            LOG.fine("Dropping notification for unregistered type: " + type);
            return;
        }

        handlerExecutor.execute(() -> invokeNotification(ctx, entry));
    }

    @SuppressWarnings({ "unchecked" })
    private void invokeNotification(FrameContext<WsMessage<JsonNode>> ctx, HandlerEntry<?, ?> entry) {
        WsMessage<JsonNode> message = ctx.body();
        try {
            Object param = convertParam(message.getPayload(), (HandlerEntry<Object, Object>) entry);
            Object result = ((HandlerEntry<Object, Object>) entry).fn.apply(param);
            if (result instanceof StreamReply) {
                LOG.info("Dropping stream reply for notification type=" + message.getType());
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Notification handler failed for type=" + message.getType(), e);
        }
    }

    @SuppressWarnings({ "unchecked" })
    private void invokeHandler(FrameContext<WsMessage<JsonNode>> ctx, HandlerEntry<?, ?> entry) {
        WsMessage<JsonNode> message = ctx.body();
        WsSession origin = ctx.origin();
        try {
            Object param = convertParam(message.getPayload(), (HandlerEntry<Object, Object>) entry);
            Object result = ((HandlerEntry<Object, Object>) entry).fn.apply(param);
            if (result instanceof StreamReply reply) {
                emitReplyStream(ctx, reply);
                return;
            }
            WsMessage<?> response = message.reply(WsProtocol.RESPONSE_TYPE, result);
            sendTo(origin, response);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "RPC handler failed for type=" + message.getType(), e);
            try {
                String detail = e.getMessage() != null ? e.getMessage() : e.toString();
                WsMessage<?> error = message.reply(WsProtocol.RESPONSE_ERROR_TYPE, detail);
                sendTo(origin, error);
            } catch (Exception sendError) {
                LOG.log(Level.WARNING, "Failed to send RPC error frame", sendError);
            }
        }
    }

    /**
     * Answer an incoming request with a stream instead of a single frame.
     *
     * <p>
     * The requester's pending future resolves with the assembled bytes once
     * its peer processes the reply's {@code done} envelope.
     */
    private void emitReplyStream(FrameContext<WsMessage<JsonNode>> ctx, StreamReply reply) {
        WsMessage<JsonNode> request = ctx.body();
        byte[] bytes = reply.data();
        UUID streamId = UUID.randomUUID();
        String sha256 = FileChunkStreamer.sha256Hex(bytes);
        List<ByteBuffer> chunks = FileChunkStreamer.chunk(streamId, bytes);
        JsonNode metaNode = reply.metadata() == null ? NullNode.getInstance()
                : mapper.valueToTree(reply.metadata());
        WsSession s = ctx.origin();
        if (!s.isOpen()) {
            LOG.info("Dropping stream reply, no open session for type=" + request.getType());
            return;
        }
        try {
            WsMessage<StreamStart> start = Frames.streamEnvelope(WsProtocol.STREAM_REPLY_START_TYPE,
                    request.getType(), request.getId(),
                    new StreamStart(streamId, metaNode, chunks.size(), sha256));
            s.sendText(mapper.writeValueAsString(start));
            for (ByteBuffer frame : chunks) {
                if (!s.isOpen()) {
                    LOG.info("Dropping stream reply mid-stream for type=" + request.getType());
                    return;
                }
                s.sendBinary(frame);
            }
            WsMessage<StreamDone> done = Frames.streamEnvelope(WsProtocol.STREAM_REPLY_DONE_TYPE,
                    request.getType(), request.getId(),
                    new StreamDone(streamId, sha256));
            s.sendText(mapper.writeValueAsString(done));
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to send stream reply", e);
            String detail = e.getMessage() != null ? e.getMessage() : e.toString();
            sink.send(ctx.origin(), Frames.responseError(request.getId(), request.getType(), detail));
        }
    }

    private void sendTo(WsSession s, WsMessage<?> message) {
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

    private Object convertParam(JsonNode payload, HandlerEntry<?, ?> entry) {
        return Jsons.deserialize(mapper, payload, entry.requestClass, "RPC param");
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
            } catch (IOException e) {
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
}
