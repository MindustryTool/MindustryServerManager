package gateway.rpc;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

public record StreamStart(
        UUID streamId,
        String streamType,
        JsonNode metadata,
        int totalChunks,
        String sha256) {
}
