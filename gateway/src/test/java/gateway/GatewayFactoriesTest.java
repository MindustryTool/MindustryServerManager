package gateway;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import gateway.rpc.WsRpcChannel;

class GatewayFactoriesTest {

    @Test
    void channelFactoriesProduceUsableChannels() {
        WsRpcChannel a = WsRpcChannel.create();
        WsRpcChannel b = WsRpcChannel.withExecutor(Executors.newSingleThreadExecutor());
        WsRpcChannel c = WsRpcChannel.withMapper(new ObjectMapper(), Runnable::run);

        assertEquals(0, a.pendingCount());
        assertEquals(0, b.pendingCount());
        assertEquals(0, c.pendingCount());

        a.shutdown();
        b.shutdown();
        c.shutdown();
    }
}
