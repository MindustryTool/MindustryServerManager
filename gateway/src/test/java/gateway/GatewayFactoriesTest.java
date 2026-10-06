package gateway;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import gateway.client.JdkWsClient;
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

    @Test
    void channelHasNoSessionBeforeConnect() {
        WsRpcChannel channel = WsRpcChannel.create();
        JdkWsClient client = JdkWsClient.connectTo(
                URI.create("ws://localhost:1/gateway"), "token", channel);

        assertNull(channel.getSession(), "stateless channel exposes a session only after open");
        assertFalse(client.isOpen());

        client.close();
        channel.shutdown();
    }
}
