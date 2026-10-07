package gateway.rpc;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;

import gateway.session.WsSession;

final class StreamSlot {
    final UUID streamId;
    final UUID startId;
    final String streamType;
    final JsonNode metadata;
    final int totalChunks;
    final String sha256;
    final UUID replyRequestId;
    final WsSession origin;
    final Map<Integer, Integer> seen = new ConcurrentHashMap<>();
    final AtomicInteger receivedBytes = new AtomicInteger();
    long reservedAtNanos = System.nanoTime();

    StreamSlot(UUID streamId, UUID startId, String streamType, JsonNode metadata, int totalChunks,
            String sha256, UUID replyRequestId, WsSession origin) {
        this.streamId = streamId;
        this.startId = startId;
        this.streamType = streamType;
        this.metadata = metadata;
        this.totalChunks = totalChunks;
        this.sha256 = sha256;
        this.replyRequestId = replyRequestId;
        this.origin = origin;
    }
}
