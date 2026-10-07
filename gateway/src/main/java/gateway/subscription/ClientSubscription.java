package gateway.rpc;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;

final class ClientSubscriptionSlot {
    final UUID id;
    final String eventType;
    final Consumer<JsonNode> handler;
    final CompletableFuture<Void> ackFuture;
    final List<Runnable> onCloseCallbacks = new CopyOnWriteArrayList<>();
    volatile boolean closed = false;
    volatile ScheduledFuture<?> timeoutTask;

    ClientSubscriptionSlot(UUID id, String eventType, Consumer<JsonNode> handler,
            CompletableFuture<Void> ackFuture) {
        this.id = id;
        this.eventType = eventType;
        this.handler = handler;
        this.ackFuture = ackFuture;
    }
}
