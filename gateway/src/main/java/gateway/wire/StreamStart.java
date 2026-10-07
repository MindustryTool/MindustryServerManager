package gateway.rpc;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Byte-stream envelope payload. The stream handler name travels in the
 * frame {@code type}, never here.
 */
public record StreamStart(
        UUID streamId,
        JsonNode metadata,
        int totalChunks,
        String sha256) {
}
