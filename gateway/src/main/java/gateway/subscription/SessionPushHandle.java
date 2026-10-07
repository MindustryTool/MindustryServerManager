package gateway.rpc;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

import gateway.session.WsSession;

final class DefaultPushHandle implements PushHandle {

    private static final Logger LOG = Logger.getLogger(DefaultPushHandle.class.getName());

    private final UUID subscribeId;
    private final String eventType;
    private final WsSession origin;
    private final FrameSink sink;
    private final List<Runnable> closeCallbacks = new CopyOnWriteArrayList<>();
    private volatile boolean closed = false;

    DefaultPushHandle(UUID subscribeId, String eventType, WsSession origin, FrameSink sink) {
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
        sink.send(origin, Frames.eventFrame(eventType, subscribeId, event));
    }

    @Override
    public void complete() {
        if (closed) {
            return;
        }
        closed = true;
        sink.send(origin, Frames.listenEnded(eventType, subscribeId));
        triggerCloseCallbacks();
    }

    @Override
    public void fail(String reason) {
        if (closed) {
            return;
        }
        closed = true;
        sink.send(origin, Frames.listenError(eventType, subscribeId, reason));
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
                LOG.log(Level.WARNING, "PushHandle onClose callback failed", e);
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
                LOG.log(Level.WARNING, "PushHandle onClose callback failed", e);
            }
        }
    }
}
