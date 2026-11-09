package gateway.client;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import gateway.wire.WsProtocol;

/**
 * Two-lane outbound buffer with control interleave.
 *
 * <p>Control items (text, ping, close) cut in between binary chunks,
 * never waiting for the full stream, while per-stream order
 * ({@code start < chunks < done}) is preserved by routing stream
 * envelopes with their chunks. After at most
 * {@link #CONTROL_INTERLEAVE_INTERVAL} consecutive control items the
 * next binary item is forced, and vice versa. The holder never blocks
 * waiting for the other lane.
 */
public final class OutboundInterleave<T> {

    public static final int CONTROL_INTERLEAVE_INTERVAL = 3;

    private final Queue<T> controlQueue = new ConcurrentLinkedQueue<>();
    private final Queue<T> binaryQueue = new ConcurrentLinkedQueue<>();
    private int controlRun;

    public void offer(T item, boolean binaryLane) {
        (binaryLane ? binaryQueue : controlQueue).offer(item);
    }

    public T pollNext() {
        boolean hasControl = !controlQueue.isEmpty();
        boolean hasBinary = !binaryQueue.isEmpty();
        if (!hasControl && !hasBinary) {
            return null;
        }
        if (hasControl && hasBinary) {
            if (controlRun >= CONTROL_INTERLEAVE_INTERVAL) {
                controlRun = 0;
                return binaryQueue.poll();
            }
            return controlQueue.poll();
        }
        return hasControl ? controlQueue.poll() : binaryQueue.poll();
    }

    /** Record that a lane item went out (call after {@link #pollNext()}). */
    public void sent(boolean binaryLane) {
        if (binaryLane) {
            controlRun = 0;
        } else {
            controlRun++;
        }
    }

    public boolean isEmpty() {
        return controlQueue.isEmpty() && binaryQueue.isEmpty();
    }

    public int depth() {
        return controlQueue.size() + binaryQueue.size();
    }

    public void clear() {
        controlQueue.clear();
        binaryQueue.clear();
    }

    /** Envelope kinds that must stay with their chunks. */
    public static boolean isStreamEnvelope(String text) {
        int i = text.indexOf("\"kind\":\"");
        if (i < 0) {
            return false;
        }
        String rest = text.substring(i + 8);
        return rest.startsWith(WsProtocol.STREAM_START_TYPE)
                || rest.startsWith(WsProtocol.STREAM_DONE_TYPE)
                || rest.startsWith(WsProtocol.STREAM_REPLY_START_TYPE)
                || rest.startsWith(WsProtocol.STREAM_REPLY_DONE_TYPE);
    }
}
