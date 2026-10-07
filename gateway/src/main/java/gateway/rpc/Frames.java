package gateway.rpc;

import java.util.UUID;

import gateway.WsMessage;

final class Frames {

    private Frames() {
    }

    static <P> WsMessage<P> streamEnvelope(String kind, String type, UUID responseOf, P payload) {
        WsMessage<P> message = WsMessage.<P>create(kind).setType(type).setPayload(payload);
        if (responseOf != null) {
            message.setResponseOf(responseOf);
        }
        return message;
    }

    static WsMessage<?> response(UUID responseOf, String type, Object payload) {
        return newEnvelope(WsProtocol.RESPONSE_TYPE, type, responseOf, payload);
    }

    static WsMessage<?> responseError(UUID responseOf, String type, String detail) {
        return newEnvelope(WsProtocol.RESPONSE_ERROR_TYPE, type, responseOf, detail);
    }

    static WsMessage<?> listeningAck(String event, UUID listenId) {
        WsMessage<Object> message = WsMessage.create(WsProtocol.LISTENING_TYPE);
        message.setEvent(event).setResponseOf(listenId).setPayload(null);
        return message;
    }

    static WsMessage<?> eventFrame(String event, UUID listenId, Object payload) {
        WsMessage<Object> message = WsMessage.create(WsProtocol.EVENT_TYPE);
        message.setEvent(event).setResponseOf(listenId).setPayload(payload);
        return message;
    }

    static WsMessage<?> listenEnded(String event, UUID listenId) {
        WsMessage<Object> message = WsMessage.create(WsProtocol.LISTEN_ENDED_TYPE);
        message.setEvent(event).setResponseOf(listenId).setPayload(null);
        return message;
    }

    static WsMessage<?> listenError(String event, UUID listenId, String detail) {
        WsMessage<Object> message = WsMessage.create(WsProtocol.LISTEN_ERROR_TYPE);
        message.setEvent(event).setResponseOf(listenId).setPayload(detail);
        return message;
    }

    static WsMessage<?> ackFor(StreamSlot slot, Object result) {
        if (result instanceof WsMessage) {
            throw new IllegalArgumentException("Stream result must not be a WsMessage");
        }
        return response(slot.startId, slot.streamType, result);
    }

    private static WsMessage<?> newEnvelope(String kind, String type, UUID responseOf, Object payload) {
        WsMessage<Object> message = WsMessage.create(kind);
        message.setType(type).setResponseOf(responseOf).setPayload(payload);
        return message;
    }
}
