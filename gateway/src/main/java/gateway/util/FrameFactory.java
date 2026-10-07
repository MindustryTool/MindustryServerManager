package gateway.util;


import java.util.UUID;

import gateway.wire.WsMessage;
import gateway.wire.WsProtocol;

public final class FrameFactory {

    private FrameFactory() {
    }

    public static <P> WsMessage<P> streamEnvelope(String kind, String type, UUID responseOf, P payload) {
        WsMessage<P> message = WsMessage.<P>create(kind).setType(type).setPayload(payload);
        if (responseOf != null) {
            message.setResponseOf(responseOf);
        }
        return message;
    }

    public static WsMessage<?> response(UUID responseOf, String type, Object payload) {
        return newEnvelope(WsProtocol.RESPONSE_TYPE, type, responseOf, payload);
    }

    public static WsMessage<?> responseError(UUID responseOf, String type, String detail) {
        return newEnvelope(WsProtocol.RESPONSE_ERROR_TYPE, type, responseOf, detail);
    }

    public static WsMessage<?> subscribedAck(String event, UUID listenId) {
        WsMessage<Object> message = WsMessage.create(WsProtocol.SUBSCRIBED_TYPE);
        message.setEvent(event).setResponseOf(listenId).setPayload(null);
        return message;
    }

    public static WsMessage<?> eventFrame(String event, UUID listenId, Object payload) {
        WsMessage<Object> message = WsMessage.create(WsProtocol.EVENT_TYPE);
        message.setEvent(event).setResponseOf(listenId).setPayload(payload);
        return message;
    }

    public static WsMessage<?> subscriptionEnded(String event, UUID listenId) {
        WsMessage<Object> message = WsMessage.create(WsProtocol.SUBSCRIPTION_ENDED_TYPE);
        message.setEvent(event).setResponseOf(listenId).setPayload(null);
        return message;
    }

    public static WsMessage<?> subscriptionError(String event, UUID listenId, String detail) {
        WsMessage<Object> message = WsMessage.create(WsProtocol.SUBSCRIPTION_ERROR_TYPE);
        message.setEvent(event).setResponseOf(listenId).setPayload(detail);
        return message;
    }

    public static WsMessage<?> ackFor(UUID startId, String streamType, Object result) {
        if (result instanceof WsMessage) {
            throw new IllegalArgumentException("Stream result must not be a WsMessage");
        }
        return response(startId, streamType, result);
    }

    private static WsMessage<?> newEnvelope(String kind, String type, UUID responseOf, Object payload) {
        WsMessage<Object> message = WsMessage.create(kind);
        message.setType(type).setResponseOf(responseOf).setPayload(payload);
        return message;
    }
}
