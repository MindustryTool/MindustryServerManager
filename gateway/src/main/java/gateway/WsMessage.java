package gateway;

import java.util.UUID;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/**
 * Canonical WebSocket RPC envelope.
 *
 * <p>Fields:
 * <ul>
 * <li>{@code id} - unique message id for correlation</li>
 * <li>{@code type} - message / handler type</li>
 * <li>{@code payload} - arbitrary JSON payload</li>
 * <li>{@code responseOf} - when set, this message is a response to the request with that id</li>
 * <li>{@code error} - true when payload carries an error description</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@Accessors(chain = true)
public class WsMessage<T> {
    private UUID id;
    private String type;
    private T payload;
    private UUID responseOf;
    private boolean error = false;

    public static <TT> WsMessage<TT> create(String type) {
        WsMessage<TT> message = new WsMessage<>();
        message.setId(UUID.randomUUID())
                .setType(type);
        return message;
    }

    public <TT> WsMessage<TT> response(TT payload) {
        return reply(payload, false);
    }

    public WsMessage<?> error(Object payload) {
        return reply(payload, true);
    }

    private <TT> WsMessage<TT> reply(TT payload, boolean error) {
        rejectNestedPayload(payload);
        WsMessage<TT> message = new WsMessage<>();
        message.setId(UUID.randomUUID())
                .setType(type)
                .setResponseOf(id)
                .setPayload(payload)
                .setError(error);
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
