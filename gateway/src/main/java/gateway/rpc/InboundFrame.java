package gateway.rpc;

import java.util.Objects;

import gateway.session.WsSession;

/**
 * One inbound text frame bound to the session that delivered it.
 *
 * <p>Answers and pushes produced from this frame MUST be sent on
 * {@link #origin()}, never on the channel's mutable current session: the
 * socket that delivered the frame is the only peer that can correlate the
 * request or owns the listener.
 *
 * <p>The body is generic so a different frame shape (e.g. a binary chunk)
 * can reuse this carrier without a new type.
 *
 * @param body   the parsed inbound frame
 * @param origin the session that delivered the frame
 */
record FrameContext<T>(T body, WsSession origin) {
    FrameContext {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(origin, "origin");
    }
}
