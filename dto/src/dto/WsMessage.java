package dto;

import java.util.UUID;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class WsMessage<T> {
    private UUID id;
    private String type;
    private T payload;
    private UUID responseOf;

    public static <TT> WsMessage<TT> create(String type) {
        WsMessage<TT> message = new WsMessage<>();
        message.setId(UUID.randomUUID());
        message.setType(type);
        return message;
    }

    public <TT> WsMessage<TT> response(TT payload) {
        WsMessage<TT> response = new WsMessage<>();
        response.setId(UUID.randomUUID());
        response.setType(type);
        response.setResponseOf(id);
        response.setPayload(payload);
        return response;
    }

    public WsMessage<T> withPayload(T payload) {
        this.payload = payload;
        return this;
    }
}
