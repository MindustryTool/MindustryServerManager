package gateway.rpc;

import gateway.session.WsSession;
import gateway.wire.WsMessage;

public interface FrameWriter {
    void send(WsSession target, WsMessage<?> message);
}
