package gateway.rpc;

import gateway.WsMessage;
import gateway.session.WsSession;

interface FrameSink {
    void send(WsSession target, WsMessage<?> message);
}
