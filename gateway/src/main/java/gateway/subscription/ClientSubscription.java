package gateway.subscription;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Consumer;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

final class ClientSubscription {
    final UUID id;
    final String eventType;
    final Consumer<JsonNode> handler;
    final CompletableFuture<Void> ackFuture;
    final List<Runnable> onCloseCallbacks = new CopyOnWriteArrayList<>();
    volatile boolean closed = false;
    volatile ScheduledFuture<?> timeoutTask;

    ClientSubscription(UUID id, String eventType, Consumer<JsonNode> handler,
            CompletableFuture<Void> ackFuture) {
        this.id = id;
        this.eventType = eventType;
        this.handler = handler;
        this.ackFuture = ackFuture;
    }
}
