package gateway.rpc;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GatewayFactoriesTest {

    @Test
    void channelFactoriesProduceUsableChannels() {
        RpcChannel a = RpcChannel.create();
        RpcChannel b = RpcChannel.withExecutor(Executors.newSingleThreadExecutor());
        RpcChannel c = RpcChannel.withMapper(new ObjectMapper(), Runnable::run);

        assertEquals(0, a.pendingCount());
        assertEquals(0, b.pendingCount());
        assertEquals(0, c.pendingCount());

        a.shutdown();
        b.shutdown();
        c.shutdown();
    }

    @Test
    void externalSchedulerIsNotTerminatedOnChannelShutdown() {
        ScheduledExecutorService sharedScheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            RpcChannel a = RpcChannel.withExecutor(Runnable::run, sharedScheduler);
            RpcChannel b = RpcChannel.withMapper(new ObjectMapper(), sharedScheduler, Runnable::run);

            a.shutdown();
            b.shutdown();

            assertFalse(sharedScheduler.isShutdown(), "Shared scheduler must not be shut down by channel");
        } finally {
            sharedScheduler.shutdownNow();
        }
    }
}
