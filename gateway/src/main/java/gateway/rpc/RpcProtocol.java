package gateway.rpc;


import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import gateway.session.SessionProvider;
import gateway.session.WsSession;
import gateway.stream.ChunkWriter;
import gateway.util.FrameFactory;
import gateway.util.JsonCodec;
import gateway.util.PendingRequests;
import gateway.wire.NoSessionException;
import gateway.wire.StreamDone;
import gateway.wire.StreamReply;
import gateway.wire.StreamStart;
import gateway.wire.WsMessage;
import gateway.wire.WsProtocol;

final class RpcProtocol implements PendingReplySink, PendingRequests {

    private static final Logger LOG = Logger.getLogger(RpcProtocol.class.getName());

    private final ObjectMapper mapper;
    private final ScheduledExecutorService scheduler;
    private final Executor handlerExecutor;
    private final Duration defaultTimeout;
    private final FrameWriter sink;
    private final SessionProvider sessions;

    private final Map<UUID, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final Map<UUID, ScheduledFuture<?>> timeouts = new ConcurrentHashMap<>();
    private final Map<String, RegisteredHandler<?, ?>> handlers = new ConcurrentHashMap<>();

    RpcProtocol(ObjectMapper mapper, ScheduledExecutorService scheduler, Executor handlerExecutor,
            Duration defaultTimeout, FrameWriter sink, SessionProvider sessions) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.handlerExecutor = Objects.requireNonNull(handlerExecutor, "handlerExecutor");
        this.defaultTimeout = Objects.requireNonNull(defaultTimeout, "defaultTimeout");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
    }

    <Req, Res> void registerHandler(String type, Class<Req> requestClass,
            Function<RequestContext<Req>, Res> handler) {
        if (type == null || handler == null) {
            throw new IllegalArgumentException("type and handler must not be null");
        }
        handlers.put(type, new RegisteredHandler<>(requestClass, handler));
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

    public boolean hasPending(UUID id) {
        return pending.containsKey(id);
    }

    public CompletableFuture<JsonNode> takePending(UUID id) {
        CompletableFuture<JsonNode> future = pending.remove(id);
        ScheduledFuture<?> timeout = timeouts.remove(id);
        if (timeout != null) {
            timeout.cancel(false);
        }
        return future;
    }

    public void failPending(UUID id, Throwable err) {
        CompletableFuture<JsonNode> future = takePending(id);
        if (future != null && !future.isDone()) {
            future.completeExceptionally(err);
        }
    }

    @Override
    public void failReply(UUID replyRequestId, String detail) {
        failPending(replyRequestId, new RuntimeException(detail));
    }

    public CompletableFuture<JsonNode> registerPending(UUID id, Duration timeout, String timeoutDetail) {
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
        WsSession session = sessions.current();
        if (session == null || !session.isOpen()) {
            return CompletableFuture.failedFuture(new NoSessionException(type));
        }
        WsMessage<?> request = WsMessage.create(WsProtocol.REQUEST_TYPE).setType(type).withPayload(payload);
        UUID id = request.getId();
        Duration effectiveTimeout = timeout != null ? timeout : defaultTimeout;

        return transmit(id, request, effectiveTimeout, type, session)
                .thenApply(node -> JsonCodec.convert(mapper, node, responseType));
    }

    CompletableFuture<JsonNode> transmit(UUID id, WsMessage<?> request, Duration effectiveTimeout,
            String type, WsSession session) {
        CompletableFuture<JsonNode> raw = registerPending(id, effectiveTimeout,
                "RPC request timed out: type=" + type + " id=" + id);

        try {
            if (!session.isOpen()) {
                failPending(id, new NoSessionException(type));
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
        WsSession session = sessions.current();
        if (session == null || !session.isOpen()) {
            LOG.info("Dropping notification, no open session for type=" + type);
            return;
        }
        WsMessage<?> message;
        try {
            message = WsMessage.create(WsProtocol.NOTIFICATION_TYPE).setType(type).withPayload(payload);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to build notification: " + type, e);
            return;
        }
        try {
            session.sendText(mapper.writeValueAsString(message));
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to send notification: " + type, e);
        }
    }

    void settleResponse(InboundFrame<WsMessage<JsonNode>> ctx) {
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

    void dispatchRequest(InboundFrame<WsMessage<JsonNode>> ctx) {
        WsMessage<JsonNode> message = ctx.body();
        String type = message.getType();
        RegisteredHandler<?, ?> entry = handlers.get(type);
        if (entry == null) {
            LOG.info("No RPC handler for type: " + type);
            sink.send(ctx.origin(), FrameFactory.responseError(message.getId(), type, "unknown RPC type: " + type));
            return;
        }

        handlerExecutor.execute(() -> invokeHandler(ctx, entry));
    }

    /**
     * Dispatch a notification. Notifications declare that no answer is
     * expected, so unknown types are dropped with a log and handler
     * failures are logged without any reply.
     */
    void dispatchNotification(InboundFrame<WsMessage<JsonNode>> ctx) {
        String type = ctx.body().getType();
        RegisteredHandler<?, ?> entry = handlers.get(type);
        if (entry == null) {
            LOG.fine("Dropping notification for unregistered type: " + type);
            return;
        }

        handlerExecutor.execute(() -> invokeNotification(ctx, entry));
    }

    @SuppressWarnings({ "unchecked" })
    private void invokeNotification(InboundFrame<WsMessage<JsonNode>> ctx, RegisteredHandler<?, ?> entry) {
        WsMessage<JsonNode> message = ctx.body();
        try {
            Object param = convertParam(message.getPayload(), (RegisteredHandler<Object, Object>) entry);
            RequestContext<Object> context = new RequestContext<>(ctx.origin(), message, param, sink);
            Object result = ((RegisteredHandler<Object, Object>) entry).fn.apply(context);
            if (result instanceof StreamReply) {
                LOG.info("Dropping stream reply for notification type=" + message.getType());
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Notification handler failed for type=" + message.getType(), e);
        }
    }

    @SuppressWarnings({ "unchecked" })
    private void invokeHandler(InboundFrame<WsMessage<JsonNode>> ctx, RegisteredHandler<?, ?> entry) {
        WsMessage<JsonNode> message = ctx.body();
        WsSession origin = ctx.origin();
        try {
            Object param = convertParam(message.getPayload(), (RegisteredHandler<Object, Object>) entry);
            RequestContext<Object> context = new RequestContext<>(origin, message, param, sink);
            Object result = ((RegisteredHandler<Object, Object>) entry).fn.apply(context);
            if (result instanceof StreamReply reply) {
                emitReplyStream(ctx, reply);
                return;
            }
            WsMessage<?> response = message.reply(WsProtocol.RESPONSE_TYPE, result);
            sink.send(origin, response);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "RPC handler failed for type=" + message.getType(), e);
            try {
                String detail = e.getMessage() != null ? e.getMessage() : e.toString();
                WsMessage<?> error = message.reply(WsProtocol.RESPONSE_ERROR_TYPE, detail);
                sink.send(origin, error);
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
    private void emitReplyStream(InboundFrame<WsMessage<JsonNode>> ctx, StreamReply reply) {
        WsMessage<JsonNode> request = ctx.body();
        byte[] bytes = reply.data();
        UUID streamId = UUID.randomUUID();
        String sha256 = ChunkWriter.sha256Hex(bytes);
        List<ByteBuffer> chunks = ChunkWriter.chunk(streamId, bytes);
        JsonNode metaNode = reply.metadata() == null ? NullNode.getInstance()
                : mapper.valueToTree(reply.metadata());
        WsSession s = ctx.origin();
        if (!s.isOpen()) {
            LOG.info("Dropping stream reply, no open session for type=" + request.getType());
            return;
        }
        try {
            WsMessage<StreamStart> start = FrameFactory.streamEnvelope(WsProtocol.STREAM_REPLY_START_TYPE,
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
            WsMessage<StreamDone> done = FrameFactory.streamEnvelope(WsProtocol.STREAM_REPLY_DONE_TYPE,
                    request.getType(), request.getId(),
                    new StreamDone(streamId, sha256));
            s.sendText(mapper.writeValueAsString(done));
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to send stream reply", e);
            String detail = e.getMessage() != null ? e.getMessage() : e.toString();
            sink.send(ctx.origin(), FrameFactory.responseError(request.getId(), request.getType(), detail));
        }
    }

    private Object convertParam(JsonNode payload, RegisteredHandler<?, ?> entry) {
        return JsonCodec.deserialize(mapper, payload, entry.requestClass, "RPC param");
    }
}
