package gateway;

import java.util.UUID;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/**
 * Canonical WebSocket RPC envelope (v2).
 *
 * <p>Fields:
 * <ul>
 * <li>{@code id} - unique message id for correlation</li>
 * <li>{@code kind} - frame kind (request, response, event, stream-start, ...)</li>
 * <li>{@code type} - application subject for RPC and byte-stream frames</li>
 * <li>{@code event} - event name for event-stream frames</li>
 * <li>{@code payload} - arbitrary JSON payload</li>
 * <li>{@code responseOf} - when set, this message answers or continues the frame with that id</li>
 * </ul>
 *
 * <p>There is no error flag; failure is encoded in {@code kind}
 * (e.g. {@code response-error}, {@code listen-error}).
 */
@Data
@NoArgsConstructor
@Accessors(chain = true)
public class WsMessage<T> {
    private UUID id;
    private String kind;
    private String type;
    private String event;
    private T payload;
    private UUID responseOf;

    public static <TT> WsMessage<TT> create(String kind) {
        WsMessage<TT> message = new WsMessage<>();
        message.setId(UUID.randomUUID())
                .setKind(kind);
        return message;
    }

    /**
     * Build an answer or continuation of this frame with a new kind.
     * The new frame keeps the subject fields and references this frame's id.
     */
    public <TT> WsMessage<TT> reply(String kind, TT payload) {
        rejectNestedPayload(payload);
        WsMessage<TT> message = new WsMessage<>();
        message.setId(UUID.randomUUID())
                .setKind(kind)
                .setType(type)
                .setEvent(event)
                .setResponseOf(id)
                .setPayload(payload);
        return message;
    }

    public WsMessage<T> withPayload(T payload) {
        return setPayload(payload);
    }

    public WsMessage<T> setPayload(T payload) {
        rejectNestedPayload(payload);
        this.payload = payload;
        return this;
    }

    private static void rejectNestedPayload(Object payload) {
        if (payload instanceof WsMessage) {
            throw new IllegalArgumentException("WsMessage payload must not be another WsMessage");
        }
    }
}
