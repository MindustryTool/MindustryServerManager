package dto;

import java.util.UUID;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Data
@NoArgsConstructor
@Accessors(chain = true)
public class WsMessage<T> {
    private UUID id;
    private String type;
    private T payload;
    private UUID responseOf;
    private boolean isError = false;

    public static <TT> WsMessage<TT> create(String type) {
        WsMessage<TT> message = new WsMessage<>();
        message.setId(UUID.randomUUID())
                .setType(type);
        return message;
    }

    public <TT> WsMessage<TT> response(TT payload) {
        WsMessage<TT> response = new WsMessage<>();
        response.setId(UUID.randomUUID())
                .setType(type)
                .setResponseOf(id)
                .setPayload(payload);
        return response;
    }

    public WsMessage<?> error(Object payload) {
        WsMessage<Object> error = new WsMessage<>();
        error.setId(UUID.randomUUID())
                .setType(type)
                .setResponseOf(id)
                .setPayload(payload)
                .setError(true);
        return error;
    }

    public WsMessage<T> withPayload(T payload) {
        this.payload = payload;
        return this;
    }
}
