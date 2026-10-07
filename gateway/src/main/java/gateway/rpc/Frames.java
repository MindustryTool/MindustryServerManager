package gateway.rpc;

import java.util.UUID;

import gateway.WsMessage;

final class Frames {

    private Frames() {
    }

    static <P> WsMessage<P> streamEnvelope(String type, UUID responseOf, P payload) {
        WsMessage<P> message = WsMessage.<P>create(type).setPayload(payload);
        if (responseOf != null) {
            message.setResponseOf(responseOf);
        }
        return message;
    }

    static WsMessage<?> reply(UUID responseOf, String type, Object payload, boolean error) {
        WsMessage<Object> message = new WsMessage<>();
        message.setId(UUID.randomUUID())
                .setType(type)
                .setResponseOf(responseOf)
                .setPayload(payload)
                .setError(error);
        return message;
    }

    static WsMessage<?> errorFor(UUID responseOf, String type, String detail) {
        return reply(responseOf, type, detail, true);
    }

    static WsMessage<?> ackFor(StreamSlot slot, Object result) {
        if (result instanceof WsMessage) {
            throw new IllegalArgumentException("Stream result must not be a WsMessage");
        }
        return reply(slot.startId, slot.streamType, result, false);
    }
}
