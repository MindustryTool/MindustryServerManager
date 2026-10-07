package gateway.rpc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
import java.util.function.BiFunction;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;

import gateway.WsMessage;
import gateway.session.WsSession;
import gateway.stream.FileChunkReceiver;
import gateway.stream.FileChunkStreamer;
import gateway.stream.FileTransferHeader;

final class StreamProtocol {

    private static final Logger LOG = Logger.getLogger(StreamProtocol.class.getName());

    /** Receiver slot idle window, refreshed on every stream frame. */
    static final Duration SLOT_TIMEOUT = Duration.ofSeconds(60);
    /** Absolute bound on slot life from reserve. */
    static final Duration SLOT_CEILING = Duration.ofSeconds(300);

    private final ObjectMapper mapper;
    private final ScheduledExecutorService scheduler;
    private final Executor handlerExecutor;
    private final Duration defaultTimeout;
    private final FrameSink sink;
    private final SessionGate sessions;
    private final ReplySink replies;
    private final RpcProtocol rpc;

    private final Map<String, StreamHandlerEntry<?, ?>> streamHandlers = new ConcurrentHashMap<>();
    private final Map<UUID, StreamSlot> streamSlots = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> senderStreams = new ConcurrentHashMap<>();
    private final Map<UUID, ScheduledFuture<?>> streamTimeouts = new ConcurrentHashMap<>();
    private final FileChunkReceiver streamReceiver = new FileChunkReceiver();

    StreamProtocol(ObjectMapper mapper, ScheduledExecutorService scheduler, Executor handlerExecutor,
            Duration defaultTimeout, FrameSink sink, SessionGate sessions, ReplySink replies, RpcProtocol rpc) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.handlerExecutor = Objects.requireNonNull(handlerExecutor, "handlerExecutor");
        this.defaultTimeout = Objects.requireNonNull(defaultTimeout, "defaultTimeout");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.replies = Objects.requireNonNull(replies, "replies");
        this.rpc = Objects.requireNonNull(rpc, "rpc");
    }

    static boolean handlesControl(String type) {
        return WsProtocol.STREAM_START_TYPE.equals(type)
                || WsProtocol.STREAM_DONE_TYPE.equals(type)
                || WsProtocol.STREAM_ABORT_TYPE.equals(type);
    }

    void handleControl(WsMessage<JsonNode> message) {
        String type = message.getType();
        if (WsProtocol.STREAM_START_TYPE.equals(type)) {
            handleStreamStart(message);
        } else if (WsProtocol.STREAM_DONE_TYPE.equals(type)) {
            handleStreamDone(message);
        } else if (WsProtocol.STREAM_ABORT_TYPE.equals(type)) {
            handleStreamAbort(message);
        }
    }

    <Meta, Res> void registerStreamHandler(String type, Class<Meta> metaClass, Class<Res> responseType,
            BiFunction<Meta, byte[], Res> handler) {
        if (type == null || handler == null) {
            throw new IllegalArgumentException("type and handler must not be null");
        }
        WsProtocol.rejectStreamControlType(type);
        streamHandlers.put(type, new StreamHandlerEntry<>(metaClass, responseType, handler));
    }

    void unregisterStreamHandler(String type) {
        streamHandlers.remove(type);
    }

    boolean hasStreamHandler(String type) {
        return streamHandlers.containsKey(type);
    }

    int pendingStreamCount() {
        return senderStreams.size() + streamSlots.size();
    }

    boolean abortStream(UUID streamId) {
        Objects.requireNonNull(streamId, "streamId");
        String reason = "Stream aborted: " + streamId;
        boolean removed = discardStream(streamId, reason);
        if (removed) {
            try {
                sink.send(Frames.streamEnvelope(WsProtocol.STREAM_ABORT_TYPE, null,
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
        UUID startId = senderStreams.remove(streamId);
        if (startId != null) {
            removed = true;
            rpc.failPending(startId, new RuntimeException(reason));
        }
        StreamSlot slot = streamSlots.remove(streamId);
        if (slot != null) {
            removed = true;
            cancelStreamTimeout(streamId);
            streamReceiver.abort(streamId);
            if (slot.replyRequestId != null) {
                rpc.failPending(slot.replyRequestId, new RuntimeException(reason));
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

    <Res> CompletableFuture<Res> sendStream(String type, Object metadata, ByteBuffer data,
            Class<Res> responseType, Duration timeout) {
        Objects.requireNonNull(data, "data");
        ByteBuffer dup = data.duplicate();
        byte[] bytes = new byte[dup.remaining()];
        dup.get(bytes);
        return sendStreamBytes(type, metadata, bytes, responseType, timeout);
    }

    <Res> CompletableFuture<Res> sendStream(String type, Object metadata, byte[] data,
            Class<Res> responseType, Duration timeout) {
        Objects.requireNonNull(data, "data");
        return sendStreamBytes(type, metadata, data, responseType, timeout);
    }

    <Res> CompletableFuture<Res> sendStream(String type, Object metadata, InputStream data,
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
        WsProtocol.rejectStreamControlType(type);
        Duration effectiveTimeout = timeout != null ? timeout : defaultTimeout;
        UUID streamId = UUID.randomUUID();
        String sha256 = FileChunkStreamer.sha256Hex(bytes);
        List<ByteBuffer> chunks = FileChunkStreamer.chunk(streamId, bytes);
        JsonNode metaNode = metadata == null ? NullNode.getInstance() : mapper.valueToTree(metadata);
        StreamStart start = new StreamStart(streamId, type, metaNode, chunks.size(), sha256);
        WsMessage<StreamStart> startMessage;
        WsMessage<StreamDone> doneMessage;
        try {
            startMessage = Frames.streamEnvelope(WsProtocol.STREAM_START_TYPE, null, start);
            doneMessage = Frames.streamEnvelope(WsProtocol.STREAM_DONE_TYPE, null,
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

        CompletableFuture<JsonNode> raw = rpc.registerPending(startId, effectiveTimeout,
                "RPC stream timed out: type=" + type + " stream=" + streamId);
        senderStreams.put(streamId, startId);
        raw.whenComplete((res, err) -> {
            senderStreams.remove(streamId);
        });

        sessions.awaitOpen()
                .thenAccept(s -> emitStream(startId, type, streamId, startJson, chunks, doneJson, s))
                .exceptionally(err -> {
                    rpc.failPending(startId, unwrapCompletion(err));
                    return null;
                });
        return raw.thenApply(node -> convert(node, responseType));
    }

    private void emitStream(UUID startId, String type, UUID streamId, String startJson, List<ByteBuffer> chunks,
            String doneJson, WsSession session) {
        try {
            if (!session.isOpen()) {
                rpc.failPending(startId,
                        new IllegalStateException("No open WebSocket session for RPC stream: " + type));
                return;
            }
            session.sendText(startJson);
            for (ByteBuffer frame : chunks) {
                if (!session.isOpen()) {
                    rpc.failPending(startId,
                            new IllegalStateException("Session closed mid-stream for RPC stream: " + type));
                    return;
                }
                session.sendBinary(frame);
            }
            session.sendText(doneJson);
        } catch (RuntimeException e) {
            rpc.failPending(startId, e);
        }
    }

    private static Throwable unwrapCompletion(Throwable err) {
        if (err instanceof CompletionException ce && ce.getCause() != null) {
            return ce.getCause();
        }
        return err;
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

    /**
     * Ingest one binary chunk frame for an announced stream.
     *
     * <p>
     * Unknown streams are dropped with a log. Duplicate chunk indexes are
     * ignored so redelivered frames stay harmless.
     */
    void handleBinaryMessage(ByteBuffer frame) {
        Objects.requireNonNull(frame, "frame");
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
    private void failLiveSlot(UUID streamId, StreamSlot slot, String detail) {
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
                rpc.failPending(replyTo,
                        new RuntimeException("Malformed reply stream start frame: " + e.getMessage()));
                return;
            }
            sink.send(Frames.errorFor(message.getId(), message.getType(),
                    "Malformed stream start frame: " + e.getMessage()));
            return;
        }
        if (start == null || start.streamId() == null || start.streamType() == null || start.sha256() == null
                || start.totalChunks() < 1) {
            LOG.warning("Dropping stream start with missing fields for type: " + message.getType());
            if (replyTo != null) {
                rpc.failPending(replyTo, new RuntimeException("Reply stream start is missing required fields"));
                return;
            }
            sink.send(Frames.errorFor(message.getId(), message.getType(),
                    "Stream start is missing required fields"));
            return;
        }
        if (replyTo != null) {
            if (!rpc.hasPending(replyTo)) {
                LOG.fine("No pending request for reply stream: " + start.streamId());
                return;
            }
            StreamSlot slot = new StreamSlot(start.streamId(), message.getId(), start.streamType(),
                    start.metadata(), start.totalChunks(), start.sha256(), replyTo);
            if (streamSlots.putIfAbsent(start.streamId(), slot) != null) {
                LOG.warning("Dropping duplicate reply stream start: " + start.streamId());
                sink.send(Frames.errorFor(replyTo, start.streamType(),
                        "duplicate reply stream start: " + start.streamId()));
                return;
            }
            armStreamTimeout(start.streamId());
            return;
        }
        StreamHandlerEntry<?, ?> entry = streamHandlers.get(start.streamType());
        if (entry == null) {
            LOG.info("No stream handler for type: " + start.streamType());
            sink.send(Frames.errorFor(message.getId(), start.streamType(),
                    "unknown stream type: " + start.streamType()));
            return;
        }
        StreamSlot slot = new StreamSlot(start.streamId(), message.getId(), start.streamType(),
                start.metadata(), start.totalChunks(), start.sha256(), null);
        if (streamSlots.putIfAbsent(start.streamId(), slot) != null) {
            LOG.warning("Dropping duplicate stream start: " + start.streamId());
            sink.send(Frames.errorFor(message.getId(), start.streamType(),
                    "duplicate stream start: " + start.streamId()));
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
        settleStreamFailure(removed, detail);
    }

    private void settleStreamFailure(StreamSlot slot, String detail) {
        if (slot.replyRequestId != null) {
            replies.failReply(slot.replyRequestId, detail);
        } else {
            sendStreamError(slot, detail);
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
            CompletableFuture<JsonNode> future = rpc.takePending(slot.replyRequestId);
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
            sink.send(Frames.ackFor(slot, result));
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Stream handler failed for type=" + slot.streamType, e);
            String detail = e.getMessage() != null ? e.getMessage() : e.toString();
            sendStreamError(slot, detail);
        }
    }

    private Object convertStreamMeta(JsonNode payload, StreamHandlerEntry<?, ?> entry) {
        return Jsons.deserialize(mapper, payload, entry.metaClass, "stream metadata");
    }

    private void sendStreamError(StreamSlot slot, String detail) {
        sink.send(Frames.errorFor(slot.startId, slot.streamType, detail));
    }

    private void cancelStreamTimeout(UUID streamId) {
        ScheduledFuture<?> t = streamTimeouts.remove(streamId);
        if (t != null) {
            t.cancel(false);
        }
    }

    void teardown() {
        for (ScheduledFuture<?> t : streamTimeouts.values()) {
            t.cancel(false);
        }
        streamTimeouts.clear();
        streamSlots.clear();
        senderStreams.clear();
        streamReceiver.clear();
    }
}
