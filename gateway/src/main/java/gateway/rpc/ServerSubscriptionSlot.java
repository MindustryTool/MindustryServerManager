package gateway.rpc;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import gateway.session.WsSession;

final class ServerSubscriptionSlot {
    final UUID subscribeId;
    final String eventType;
    final Object params;
    final PushHandle handle;
    final WsSession origin;
    final List<Runnable> onCloseCallbacks = new CopyOnWriteArrayList<>();
    volatile boolean closed = false;

    ServerSubscriptionSlot(UUID subscribeId, String eventType, Object params, PushHandle handle,
            WsSession origin) {
        this.subscribeId = subscribeId;
        this.eventType = eventType;
        this.params = params;
        this.handle = handle;
        this.origin = origin;
    }
}
