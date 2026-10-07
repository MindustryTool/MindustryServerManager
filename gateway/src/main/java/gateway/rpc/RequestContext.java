package gateway.rpc;

import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;

import gateway.session.WsSession;
import gateway.wire.WsMessage;

/**
 * Per-request context handed to a channel handler.
 *
 * <p>Carries the decoded request, the raw frame, and the session that
 * delivered it, so a handler can read the request and route its own sends to
 * the correct peer instead of reading the channel's volatile current session.
 *
 * @param <Req> the registered request type, accessible via {@link #body()}
 */
public final class RequestContext<Req> {

    private final WsSession session;
    private final WsMessage<JsonNode> message;
    private final Req body;
    private final FrameWriter sink;

    RequestContext(WsSession session, WsMessage<JsonNode> message, Req body, FrameWriter sink) {
        this.session = Objects.requireNonNull(session, "session");
        this.message = Objects.requireNonNull(message, "message");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.body = body;
    }

    public Req body() {
        return body;
    }

    public WsSession session() {
        return session;
    }

    public WsMessage<JsonNode> message() {
        return message;
    }

    /** Send a frame on the delivering session. */
    public void send(WsMessage<?> frame) {
        Objects.requireNonNull(frame, "frame");
        sink.send(session, frame);
    }
}
