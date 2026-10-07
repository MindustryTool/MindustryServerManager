package gateway.stream;


import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
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

import gateway.rpc.FrameWriter;
import gateway.rpc.InboundFrame;
import gateway.rpc.PendingReplySink;
import gateway.session.SessionProvider;
import gateway.session.WsSession;
import gateway.util.FrameFactory;
import gateway.util.JsonCodec;
import gateway.util.PendingRequests;
import gateway.wire.NoSessionException;
import gateway.wire.StreamAbort;
import gateway.wire.StreamDone;
import gateway.wire.StreamStart;
import gateway.wire.TooManyStreamsException;
import gateway.wire.WsMessage;
import gateway.wire.WsProtocol;

public final class StreamProtocol {

    private static final Logger LOG = Logger.getLogger(StreamProtocol.class.getName());

    /** Receiver slot idle window, refreshed on every stream frame. */
    static final Duration SLOT_TIMEOUT = Duration.ofSeconds(60);
    /** Absolute bound on slot life from reserve. */
    static final Duration SLOT_CEILING = Duration.ofSeconds(300);

    private final ObjectMapper mapper;
    private final ScheduledExecutorService scheduler;
    private final Executor handlerExecutor;
    private final Duration defaultTimeout;
    private final FrameWriter sink;
    private final SessionProvider sessions;
    private final PendingReplySink pendingReplySink;
    private final PendingRequests pendingRequests;

    private final Map<String, RegisteredStreamHandler<?, ?>> streamHandlers = new ConcurrentHashMap<>();
    private final Map<UUID, PendingStream> streamSlots = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> senderStreams = new ConcurrentHashMap<>();
    private final Map<UUID, WsSession> senderOrigins = new ConcurrentHashMap<>();
    private final Map<UUID, ScheduledFuture<?>> streamTimeouts = new ConcurrentHashMap<>();
    private final ChunkAssembler streamReceiver = new ChunkAssembler();

    public StreamProtocol(ObjectMapper mapper, ScheduledExecutorService scheduler, Executor handlerExecutor,
            Duration defaultTimeout, FrameWriter sink, SessionProvider sessions, PendingReplySink pendingReplySink, PendingRequests pendingRequests) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.handlerExecutor = Objects.requireNonNull(handlerExecutor, "handlerExecutor");
        this.defaultTimeout = Objects.requireNonNull(defaultTimeout, "defaultTimeout");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.pendingReplySink = Objects.requireNonNull(pendingReplySink, "pendingReplySink");
        this.pendingRequests = Objects.requireNonNull(pendingRequests, "pendingRequests");
    }

    public void handleControl(InboundFrame<WsMessage<JsonNode>> ctx) {
        WsMessage<JsonNode> message = ctx.body();
        String kind = message.getKind();
        if (WsProtocol.STREAM_START_TYPE.equals(kind)) {
            handleStreamStart(ctx);
        } else if (WsProtocol.STREAM_DONE_TYPE.equals(kind)) {
            handleStreamDone(message);
        } else if (WsProtocol.STREAM_REPLY_START_TYPE.equals(kind)) {
            handleReplyStreamStart(ctx);
        } else if (WsProtocol.STREAM_REPLY_DONE_TYPE.equals(kind)) {
            handleReplyStreamDone(message);
        } else if (WsProtocol.STREAM_ABORT_TYPE.equals(kind)) {
            handleStreamAbort(message);
        }
    }

    public <Meta, Res> void registerStreamHandler(String type, Class<Meta> metaClass, Class<Res> responseType,
            BiFunction<Meta, byte[], Res> handler) {
        if (type == null || handler == null) {
            throw new IllegalArgumentException("type and handler must not be null");
        }
        streamHandlers.put(type, new RegisteredStreamHandler<>(metaClass, responseType, handler));
    }

    public void unregisterStreamHandler(String type) {
        streamHandlers.remove(type);
    }

    public boolean hasStreamHandler(String type) {
        return streamHandlers.containsKey(type);
    }

    public int pendingStreamCount() {
        return senderStreams.size() + streamSlots.size();
    }

    public boolean abortStream(UUID streamId, String type) {
        Objects.requireNonNull(streamId, "streamId");
        Objects.requireNonNull(type, "type");
        PendingStream slot = streamSlots.get(streamId);
        if (slot != null && slot.replyRequestId != null) {
            return false;
        }
        String reason = "Stream aborted: " + streamId;
        WsSession target = senderOrigins.get(streamId);
        boolean removed = discardStream(streamId, reason);
        if (removed) {
            try {
                sink.send(target != null ? target : sessions.current(),
                        FrameFactory.streamEnvelope(WsProtocol.STREAM_ABORT_TYPE, type, null,
                                new StreamAbort(streamId, reason)));
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
        senderOrigins.remove(streamId);
        UUID startId = senderStreams.remove(streamId);
        if (startId != null) {
            removed = true;
            pendingRequests.failPending(startId, new RuntimeException(reason));
        }
        PendingStream slot = streamSlots.remove(streamId);
        if (slot != null) {
            removed = true;
            cancelStreamTimeout(streamId);
            streamReceiver.abort(streamId);
            if (slot.replyRequestId != null) {
                pendingRequests.failPending(slot.replyRequestId, new RuntimeException(reason));
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
        PendingStream slot = streamSlots.get(abort.streamId());
        if (slot != null && !message.getType().equals(slot.streamType)) {
            LOG.warning("Dropping stream abort with mismatched type for stream: " + abort.streamId());
            return;
        }
        String reason = abort.reason() != null ? abort.reason() : "Peer aborted stream " + abort.streamId();
        boolean removed = discardStream(abort.streamId(), reason);
        if (!removed) {
            LOG.fine("Dropping abort for unknown stream: " + abort.streamId());
        }
    }

    public <Res> CompletableFuture<Res> sendStream(String type, Object metadata, ByteBuffer data,
            Class<Res> responseType, Duration timeout) {
        Objects.requireNonNull(data, "data");
        ByteBuffer dup = data.duplicate();
        byte[] bytes = new byte[dup.remaining()];
        dup.get(bytes);
        return sendStreamBytes(type, metadata, bytes, responseType, timeout);
    }

    public <Res> CompletableFuture<Res> sendStream(String type, Object metadata, byte[] data,
            Class<Res> responseType, Duration timeout) {
        Objects.requireNonNull(data, "data");
        return sendStreamBytes(type, metadata, data, responseType, timeout);
    }

    public <Res> CompletableFuture<Res> sendStream(String type, Object metadata, InputStream data,
            Class<Res> responseType, Duration timeout) {
        Objects.requireNonNull(data, "data");
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = data.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return sendStreamBytes(type, metadata, out.toByteArray(), responseType, timeout);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read stream data for type=" + type, e);
        }
    }

    private <Res> CompletableFuture<Res> sendStreamBytes(String type, Object metadata, byte[] bytes,
            Class<Res> responseType, Duration timeout) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(bytes, "bytes");
        WsSession session = sessions.current();
        if (session == null || !session.isOpen()) {
            return CompletableFuture.failedFuture(new NoSessionException(type));
        }
        if (pendingStreamCount() >= WsProtocol.MAX_CONCURRENT_STREAMS) {
            return CompletableFuture.failedFuture(new TooManyStreamsException(type));
        }
        Duration effectiveTimeout = timeout != null ? timeout : defaultTimeout;
        UUID streamId = UUID.randomUUID();
        String sha256 = ChunkWriter.sha256Hex(bytes);
        List<ByteBuffer> chunks = ChunkWriter.chunk(streamId, bytes);
        JsonNode metaNode = metadata == null ? NullNode.getInstance() : mapper.valueToTree(metadata);
        StreamStart start = new StreamStart(streamId, metaNode, chunks.size(), sha256);
        WsMessage<StreamStart> startMessage;
        WsMessage<StreamDone> doneMessage;
        try {
            startMessage = FrameFactory.streamEnvelope(WsProtocol.STREAM_START_TYPE, type, null, start);
            doneMessage = FrameFactory.streamEnvelope(WsProtocol.STREAM_DONE_TYPE, type, null,
                    new StreamDone(streamId, sha256));
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

        CompletableFuture<JsonNode> raw = pendingRequests.registerPending(startId, effectiveTimeout,
                "RPC stream timed out: type=" + type + " stream=" + streamId);
        senderStreams.put(streamId, startId);
        raw.whenComplete((res, err) -> {
            senderStreams.remove(streamId);
            senderOrigins.remove(streamId);
        });

        emitStream(startId, type, streamId, startJson, chunks, doneJson, session);
        return raw.thenApply(node -> JsonCodec.convert(mapper, node, responseType));
    }

    private void emitStream(UUID startId, String type, UUID streamId, String startJson, List<ByteBuffer> chunks,
            String doneJson, WsSession session) {
        senderOrigins.put(streamId, session);
        try {
            if (!session.isOpen()) {
                pendingRequests.failPending(startId,
                        new IllegalStateException("No open WebSocket session for RPC stream: " + type));
                return;
            }
            session.sendText(startJson);
            for (ByteBuffer frame : chunks) {
                if (!session.isOpen()) {
                    pendingRequests.failPending(startId,
                            new IllegalStateException("Session closed mid-stream for RPC stream: " + type));
                    return;
                }
                session.sendBinary(frame);
            }
            session.sendText(doneJson);
        } catch (RuntimeException e) {
            pendingRequests.failPending(startId, e);
        }
    }

    /**
     * Ingest one binary chunk frame for an announced stream.
     *
     * <p>
     * Unknown streams are dropped with a log. Duplicate chunk indexes are
     * ignored so redelivered frames stay harmless.
     */
    public void handleBinaryMessage(ByteBuffer frame) {
        Objects.requireNonNull(frame, "frame");
        final ChunkHeader header;
        try {
            header = ChunkHeader.decode(frame.duplicate());
        } catch (RuntimeException e) {
            failOnNegativeIndex(frame);
            LOG.log(Level.WARNING, "Dropping malformed stream chunk frame: " + e.getMessage(), e);
            return;
        }
        UUID streamId = header.transferId();
        PendingStream slot = streamSlots.get(streamId);
        if (slot == null) {
            LOG.warning("Dropping chunk for unknown stream: " + streamId);
            return;
        }
        refreshStreamTimeout(streamId);
        final byte[] payload;
        try {
            payload = ChunkHeader.payloadOf(frame);
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
            if (total > WsProtocol.MAX_STREAM_BYTES) {
                streamSlots.remove(streamId);
                cancelStreamTimeout(streamId);
                streamReceiver.abort(streamId);
                settleStreamFailure(slot,
                        "Stream exceeds max bytes (" + WsProtocol.MAX_STREAM_BYTES + "): " + streamId);
                return;
            }
            try {
                streamReceiver.receive(frame);
            } catch (RuntimeException e) {
                streamSlots.remove(streamId);
                cancelStreamTimeout(streamId);
                streamReceiver.abort(streamId);
                LOG.log(Level.WARNING, "Failed to buffer chunk for stream " + streamId, e);
                settleStreamFailure(slot,
                        "Failed to buffer chunk for stream " + streamId + ": " + e.getMessage());
            }
        }
    }

    /**
     * Fail a live receiver slot at ingest time (bad chunk index). The slot is
     * removed atomically; when already gone the frame is simply dropped.
     */
    private void failLiveSlot(UUID streamId, PendingStream slot, String detail) {
        if (!streamSlots.remove(streamId, slot)) {
            return;
        }
        cancelStreamTimeout(streamId);
        streamReceiver.abort(streamId);
        settleStreamFailure(slot, detail);
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
            if (dup.remaining() < ChunkHeader.HEADER_SIZE
                    || dup.getInt(dup.position() + 16) >= 0) {
                return;
            }
            int pos = dup.position();
            streamId = new UUID(dup.getLong(pos), dup.getLong(pos + 8));
        } catch (RuntimeException e) {
            return;
        }
        PendingStream slot = streamSlots.get(streamId);
        if (slot != null) {
            failLiveSlot(streamId, slot,
                    "Negative chunk index for stream " + streamId);
        }
    }

    private void handleStreamStart(InboundFrame<WsMessage<JsonNode>> ctx) {
        WsMessage<JsonNode> message = ctx.body();
        if (message.getResponseOf() != null) {
            LOG.warning("Dropping stream start with unexpected responseOf");
            return;
        }
        final StreamStart start;
        try {
            start = mapper.treeToValue(message.getPayload(), StreamStart.class);
        } catch (JsonProcessingException e) {
            LOG.log(Level.WARNING, "Dropping malformed stream start frame: " + e.getMessage(), e);
            sink.send(ctx.origin(), FrameFactory.responseError(message.getId(), message.getType(),
                    "Malformed stream start frame: " + e.getMessage()));
            return;
        }
        String streamType = message.getType();
        if (start == null || start.streamId() == null || start.sha256() == null
                || start.totalChunks() < 1) {
            LOG.warning("Dropping stream start with missing fields for type: " + streamType);
            sink.send(ctx.origin(), FrameFactory.responseError(message.getId(), streamType,
                    "Stream start is missing required fields"));
            return;
        }
        RegisteredStreamHandler<?, ?> entry = streamHandlers.get(streamType);
        if (entry == null) {
            LOG.info("No stream handler for type: " + streamType);
            sink.send(ctx.origin(), FrameFactory.responseError(message.getId(), streamType,
                    "unknown stream type: " + streamType));
            return;
        }
        if (pendingStreamCount() >= WsProtocol.MAX_CONCURRENT_STREAMS) {
            LOG.warning("Rejecting stream start over cap: " + start.streamId());
            sink.send(ctx.origin(), FrameFactory.responseError(message.getId(), streamType,
                    "Too many concurrent streams (max " + WsProtocol.MAX_CONCURRENT_STREAMS + ")"));
            return;
        }
        final Object validatedMetadata;
        try {
            validatedMetadata = JsonCodec.deserialize(mapper, start.metadata(), entry.metaClass, "stream metadata");
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Rejecting stream start with invalid metadata: " + start.streamId(), e);
            sink.send(ctx.origin(), FrameFactory.responseError(message.getId(), streamType,
                    "Invalid stream metadata: " + e.getMessage()));
            return;
        }
        PendingStream slot = new PendingStream(start.streamId(), message.getId(), streamType,
                validatedMetadata, start.totalChunks(), start.sha256(), null, ctx.origin());
        if (streamSlots.putIfAbsent(start.streamId(), slot) != null) {
            LOG.warning("Dropping duplicate stream start: " + start.streamId());
            sink.send(ctx.origin(), FrameFactory.responseError(message.getId(), streamType,
                    "duplicate stream start: " + start.streamId()));
            return;
        }
        armStreamTimeout(start.streamId());
    }

    private void handleReplyStreamStart(InboundFrame<WsMessage<JsonNode>> ctx) {
        WsMessage<JsonNode> message = ctx.body();
        UUID replyTo = message.getResponseOf();
        if (replyTo == null) {
            LOG.warning("Dropping reply stream start without responseOf");
            return;
        }
        final StreamStart start;
        try {
            start = mapper.treeToValue(message.getPayload(), StreamStart.class);
        } catch (JsonProcessingException e) {
            LOG.log(Level.WARNING, "Dropping malformed reply stream start frame: " + e.getMessage(), e);
            pendingRequests.failPending(replyTo,
                    new RuntimeException("Malformed reply stream start frame: " + e.getMessage()));
            return;
        }
        String streamType = message.getType();
        if (start == null || start.streamId() == null || start.sha256() == null
                || start.totalChunks() < 1) {
            LOG.warning("Dropping reply stream start with missing fields for type: " + streamType);
            pendingRequests.failPending(replyTo, new RuntimeException("Reply stream start is missing required fields"));
            return;
        }
        if (!pendingRequests.hasPending(replyTo)) {
            LOG.fine("No pending request for reply stream: " + start.streamId());
            return;
        }
        if (pendingStreamCount() >= WsProtocol.MAX_CONCURRENT_STREAMS) {
            LOG.warning("Failing reply stream over cap: " + start.streamId());
            pendingRequests.failPending(replyTo, new TooManyStreamsException(streamType));
            return;
        }
        PendingStream slot = new PendingStream(start.streamId(), message.getId(), streamType,
                start.metadata(), start.totalChunks(), start.sha256(), replyTo, ctx.origin());
        if (streamSlots.putIfAbsent(start.streamId(), slot) != null) {
            LOG.warning("Dropping duplicate reply stream start: " + start.streamId());
            pendingRequests.failPending(replyTo,
                    new RuntimeException("duplicate reply stream start: " + start.streamId()));
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
        PendingStream slot = streamSlots.get(streamId);
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
            PendingStream removed = streamSlots.get(streamId);
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
        PendingStream removed = streamSlots.remove(streamId);
        streamTimeouts.remove(streamId);
        if (removed == null) {
            return;
        }
        streamReceiver.abort(streamId);
        LOG.warning(detail);
        settleStreamFailure(removed, detail);
    }

    private void settleStreamFailure(PendingStream slot, String detail) {
        if (slot.replyRequestId != null) {
            pendingReplySink.failReply(slot.replyRequestId, detail);
        } else {
            sendStreamError(slot, detail);
        }
    }

    private void handleStreamDone(WsMessage<JsonNode> message) {
        final StreamDone done = parseStreamDone(message);
        if (done == null) {
            return;
        }
        PendingStream slot = streamSlots.remove(done.streamId());
        if (slot == null) {
            LOG.fine("No pending stream for done: " + done.streamId());
            return;
        }
        if (slot.replyRequestId != null) {
            LOG.warning("Dropping stream done for a reply stream: " + done.streamId());
            streamReceiver.abort(done.streamId());
            return;
        }
        cancelStreamTimeout(done.streamId());
        completeSlot(slot, done);
    }

    private void handleReplyStreamDone(WsMessage<JsonNode> message) {
        final StreamDone done = parseStreamDone(message);
        if (done == null) {
            return;
        }
        if (message.getResponseOf() == null) {
            LOG.warning("Dropping reply stream done without responseOf");
            return;
        }
        PendingStream slot = streamSlots.remove(done.streamId());
        if (slot == null) {
            LOG.fine("No pending reply stream for done: " + done.streamId());
            return;
        }
        if (slot.replyRequestId == null) {
            LOG.warning("Dropping reply stream done for an initiating stream: " + done.streamId());
            streamReceiver.abort(done.streamId());
            return;
        }
        cancelStreamTimeout(done.streamId());
        completeSlot(slot, done);
    }

    private StreamDone parseStreamDone(WsMessage<JsonNode> message) {
        final StreamDone done;
        try {
            done = mapper.treeToValue(message.getPayload(), StreamDone.class);
        } catch (JsonProcessingException e) {
            LOG.log(Level.WARNING, "Dropping malformed stream done frame: " + e.getMessage(), e);
            return null;
        }
        if (done == null || done.streamId() == null || done.sha256() == null) {
            LOG.warning("Dropping stream done with missing fields");
            return null;
        }
        return done;
    }

    private void completeSlot(PendingStream slot, StreamDone done) {
        boolean isReply = slot.replyRequestId != null;
        if (!done.sha256().equalsIgnoreCase(slot.sha256)) {
            streamReceiver.abort(done.streamId());
            settleStreamFailure(slot, "Stream checksum header mismatch for stream " + done.streamId());
            return;
        }
        final byte[] assembled;
        try {
            assembled = streamReceiver.assemble(done.streamId(), slot.totalChunks, slot.sha256);
        } catch (SecurityException e) {
            streamReceiver.abort(done.streamId());
            settleStreamFailure(slot, "Checksum mismatch for stream " + done.streamId());
            return;
        } catch (IllegalStateException e) {
            streamReceiver.abort(done.streamId());
            settleStreamFailure(slot, "Incomplete stream " + done.streamId() + ": " + e.getMessage());
            return;
        }
        if (assembled.length > WsProtocol.MAX_STREAM_BYTES) {
            settleStreamFailure(slot,
                    "Stream exceeds max bytes (" + WsProtocol.MAX_STREAM_BYTES + "): " + done.streamId());
            return;
        }
        if (isReply) {
            CompletableFuture<JsonNode> future = pendingRequests.takePending(slot.replyRequestId);
            if (future != null && !future.isDone()) {
                future.complete(mapper.getNodeFactory().binaryNode(assembled));
            }
            return;
        }
        RegisteredStreamHandler<?, ?> entry = streamHandlers.get(slot.streamType);
        if (entry == null) {
            sendStreamError(slot, "unknown stream type: " + slot.streamType);
            return;
        }
        handlerExecutor.execute(() -> invokeStreamHandler(slot, entry, assembled));
    }

    @SuppressWarnings({ "unchecked" })
    private void invokeStreamHandler(PendingStream slot, RegisteredStreamHandler<?, ?> entry, byte[] assembled) {
        try {
            Object meta = slot.metadata;
            Object result = ((RegisteredStreamHandler<Object, Object>) entry).fn.apply(meta, assembled);
            sink.send(slot.origin, FrameFactory.ackFor(slot.startId, slot.streamType, result));
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Stream handler failed for type=" + slot.streamType, e);
            String detail = e.getMessage() != null ? e.getMessage() : e.toString();
            sendStreamError(slot, detail);
        }
    }

    private void sendStreamError(PendingStream slot, String detail) {
        sink.send(slot.origin, FrameFactory.responseError(slot.startId, slot.streamType, detail));
    }

    private void cancelStreamTimeout(UUID streamId) {
        ScheduledFuture<?> t = streamTimeouts.remove(streamId);
        if (t != null) {
            t.cancel(false);
        }
    }

    public void teardown() {
        for (ScheduledFuture<?> t : streamTimeouts.values()) {
            t.cancel(false);
        }
        streamTimeouts.clear();
        streamSlots.clear();
        senderStreams.clear();
        senderOrigins.clear();
        streamReceiver.clear();
    }
}
