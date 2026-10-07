package gateway.rpc;

import gateway.WsMessage;

interface FrameSink {
    void send(WsMessage<?> message);
}
