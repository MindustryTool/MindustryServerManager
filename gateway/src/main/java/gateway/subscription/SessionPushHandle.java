package gateway.subscription;


import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.Objects;
import java.util.UUID;

import gateway.rpc.FrameWriter;
import gateway.session.WsSession;
import gateway.util.FrameFactory;

final class SessionPushHandle implements SubscriptionHandle {

    private static final Logger LOG = Logger.getLogger(SessionPushHandle.class.getName());

    private final UUID subscribeId;
    private final String eventType;
    private final WsSession origin;
    private final FrameWriter sink;
    private final List<Runnable> closeCallbacks = new CopyOnWriteArrayList<>();
    private volatile boolean closed = false;

    SessionPushHandle(UUID subscribeId, String eventType, WsSession origin, FrameWriter sink) {
        this.subscribeId = subscribeId;
        this.eventType = eventType;
        this.origin = origin;
        this.sink = sink;
    }

    @Override
    public void push(Object event) {
        if (closed) {
            return;
        }
        sink.send(origin, FrameFactory.eventFrame(eventType, subscribeId, event));
    }

    @Override
    public void complete() {
        if (closed) {
            return;
        }
        closed = true;
        sink.send(origin, FrameFactory.subscriptionEnded(eventType, subscribeId));
        triggerCloseCallbacks();
    }

    @Override
    public void fail(String reason) {
        if (closed) {
            return;
        }
        closed = true;
        sink.send(origin, FrameFactory.subscriptionError(eventType, subscribeId, reason));
        triggerCloseCallbacks();
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void onClose(Runnable callback) {
        Objects.requireNonNull(callback, "callback");
        if (closed) {
            try {
                callback.run();
            } catch (Exception e) {
                LOG.log(Level.WARNING, "SubscriptionHandle onClose callback failed", e);
            }
        } else {
            closeCallbacks.add(callback);
        }
    }

    private void triggerCloseCallbacks() {
        for (Runnable cb : closeCallbacks) {
            try {
                cb.run();
            } catch (Exception e) {
                LOG.log(Level.WARNING, "SubscriptionHandle onClose callback failed", e);
            }
        }
    }
}
