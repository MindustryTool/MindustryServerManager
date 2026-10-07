package gateway.subscription;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.util.UUID;

import gateway.session.WsSession;

final class ServerSubscription {
    final UUID subscribeId;
    final String eventType;
    final SubscriptionHandle handle;
    final WsSession origin;
    final List<Runnable> onCloseCallbacks = new CopyOnWriteArrayList<>();
    volatile boolean closed = false;

    ServerSubscription(UUID subscribeId, String eventType, SubscriptionHandle handle,
            WsSession origin) {
        this.subscribeId = subscribeId;
        this.eventType = eventType;
        this.handle = handle;
        this.origin = origin;
    }
}
