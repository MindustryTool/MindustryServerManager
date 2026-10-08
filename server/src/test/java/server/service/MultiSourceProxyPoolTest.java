package server.service;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;
import server.service.proxy.ProxySource;

import static org.junit.jupiter.api.Assertions.*;

public class MultiSourceProxyPoolTest {

    private static List<ProxySource> noSources() {
        return Collections.emptyList();
    }

    @Test
    public void testLeastSentSelectionRotatesAndBreaksTiesByOrder() {
        MultiSourceProxyPool pool = new MultiSourceProxyPool(noSources());
        InetSocketAddress p1 = new InetSocketAddress("1.1.1.1", 80);
        InetSocketAddress p2 = new InetSocketAddress("2.2.2.2", 80);
        pool.addProxies(List.of(p1, p2));
        assertEquals(2, pool.size());

        // Both start at 0 -> first in list wins, then the other, then the first again.
        assertEquals(p1, pool.acquire().address());
        assertEquals(p2, pool.acquire().address());
        assertEquals(p1, pool.acquire().address());

        assertEquals(2, pool.getTracked(p1).getSentCount());
        assertEquals(1, pool.getTracked(p2).getSentCount());
        pool.release();
    }

    @Test
    public void testNewProxiesStartAtZeroAndKeepExistingCounts() {
        MultiSourceProxyPool pool = new MultiSourceProxyPool(noSources());
        InetSocketAddress p1 = new InetSocketAddress("1.1.1.1", 80);
        pool.addProxies(List.of(p1));
        assertEquals(p1, pool.acquire().address());
        pool.release();
        assertEquals(1, pool.getTracked(p1).getSentCount());

        InetSocketAddress p2 = new InetSocketAddress("2.2.2.2", 80);
        pool.addProxies(List.of(p2));

        assertEquals(0, pool.getTracked(p2).getSentCount(), "New proxy must start at 0");
        assertEquals(1, pool.getTracked(p1).getSentCount(), "Existing proxy keeps its count");
        assertEquals(p2, pool.acquire().address(), "Least-sent new proxy is picked next");
        pool.release();
    }

    @Test
    public void testEvictionAfterThreeConsecutiveFailures() {
        MultiSourceProxyPool pool = new MultiSourceProxyPool(noSources());
        InetSocketAddress p1 = new InetSocketAddress("1.1.1.1", 80);
        pool.addProxies(List.of(p1));
        TrackedProxy proxy = pool.getTracked(p1);

        pool.recordFailure(proxy);
        pool.recordFailure(proxy);
        assertEquals(1, pool.size(), "Proxy survives two failures");
        assertEquals(2, proxy.getConsecutiveFailures());

        pool.recordFailure(proxy);
        assertEquals(0, pool.size(), "Proxy evicted on third consecutive failure");
    }

    @Test
    public void testSuccessResetsConsecutiveFailures() {
        MultiSourceProxyPool pool = new MultiSourceProxyPool(noSources());
        InetSocketAddress p1 = new InetSocketAddress("1.1.1.1", 80);
        pool.addProxies(List.of(p1));
        TrackedProxy proxy = pool.getTracked(p1);

        pool.recordFailure(proxy);
        pool.recordFailure(proxy);
        pool.recordSuccess(proxy);
        assertEquals(0, proxy.getConsecutiveFailures());

        pool.recordFailure(proxy);
        pool.recordFailure(proxy);
        assertEquals(1, pool.size(), "Failures after a success must not evict");
    }

    @Test
    public void testSelectorUsesAcquiredProxyOnCallingThread() {
        MultiSourceProxyPool pool = new MultiSourceProxyPool(noSources());
        InetSocketAddress p1 = new InetSocketAddress("1.1.1.1", 8080);
        pool.addProxies(List.of(p1));

        ProxySelector selector = pool.asProxySelector();
        URI uri = URI.create("https://translate.googleapis.com");

        assertEquals(Proxy.NO_PROXY, selector.select(uri).get(0));

        pool.acquire();
        List<Proxy> proxies = selector.select(uri);
        assertEquals(1, proxies.size());
        assertEquals(Proxy.Type.HTTP, proxies.get(0).type());
        assertEquals(p1, proxies.get(0).address(), "Selector must use the pool-picked proxy");
        pool.release();

        assertEquals(Proxy.NO_PROXY, selector.select(uri).get(0));
    }

    @Test
    public void testEagerFetchOnCreation() throws Exception {
        InetSocketAddress customProxy = new InetSocketAddress("10.0.0.1", 8080);
        AtomicInteger fetchCount = new AtomicInteger();
        ProxySource mockSource = new ProxySource() {
            @Override
            public String name() {
                return "mock";
            }

            @Override
            public List<InetSocketAddress> fetch() {
                fetchCount.incrementAndGet();
                return List.of(customProxy);
            }
        };

        MultiSourceProxyPool pool = new MultiSourceProxyPool(mockSource);
        assertEquals(1, pool.getSources().size());

        await(() -> pool.size() == 1);
        assertEquals(customProxy, pool.acquire().address());
        pool.release();
        assertTrue(fetchCount.get() >= 1, "Pool must fetch eagerly on creation");
    }

    @Test
    public void testLowWatermarkRefillIsDebounced() throws Exception {
        InetSocketAddress customProxy = new InetSocketAddress("10.0.0.1", 8080);
        AtomicInteger fetchCount = new AtomicInteger();
        ProxySource mockSource = new ProxySource() {
            @Override
            public String name() {
                return "mock";
            }

            @Override
            public List<InetSocketAddress> fetch() {
                fetchCount.incrementAndGet();
                return List.of(customProxy);
            }
        };

        MultiSourceProxyPool pool = new MultiSourceProxyPool(mockSource);
        await(() -> pool.size() == 1);
        int afterInitial = fetchCount.get();

        // Drain to zero, then hammer the low-watermark trigger within the debounce window.
        TrackedProxy drained = pool.acquire();
        pool.release();
        for (int i = 0; i < 3; i++) {
            pool.recordFailure(drained);
        }
        assertEquals(0, pool.size());

        pool.checkAndTriggerRefresh();
        pool.checkAndTriggerRefresh();
        Thread.sleep(100);

        assertEquals(afterInitial, fetchCount.get(),
                "Refill must be debounced even when the pool is empty");
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        fail("Condition not met within timeout");
    }
}
