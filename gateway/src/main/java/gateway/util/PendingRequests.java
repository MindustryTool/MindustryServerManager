package gateway.util;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Narrow bridge to the RPC pending-request registry, used by stream settlement
 * so the stream area does not depend on the concrete RPC engine.
 */
public interface PendingRequests {

    CompletableFuture<JsonNode> registerPending(UUID id, Duration timeout, String timeoutDetail);

    void failPending(UUID id, Throwable err);

    boolean hasPending(UUID id);

    CompletableFuture<JsonNode> takePending(UUID id);
}
