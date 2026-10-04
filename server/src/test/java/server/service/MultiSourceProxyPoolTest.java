package server.service;

import java.net.InetSocketAddress;
import java.util.List;

import org.junit.jupiter.api.Test;
import server.service.proxy.ProxySource;

import static org.junit.jupiter.api.Assertions.*;

public class MultiSourceProxyPoolTest {

    @Test
    public void testCandidateRotationAndEviction() {
        MultiSourceProxyPool pool = new MultiSourceProxyPool();
        InetSocketAddress p1 = new InetSocketAddress("1.1.1.1", 80);
        InetSocketAddress p2 = new InetSocketAddress("2.2.2.2", 80);

        pool.addProxies(List.of(p1, p2));
        assertEquals(2, pool.size());

        // Cycles through pool
        InetSocketAddress first = pool.getNextCandidate();
        InetSocketAddress second = pool.getNextCandidate();
        assertNotNull(first);
        assertNotNull(second);
        assertNotEquals(first, second);

        // Evict p1
        pool.evict(p1);
        assertEquals(1, pool.size());
        assertEquals(p2, pool.getNextCandidate());
    }

    @Test
    public void testCustomProxySourceRefresh() {
        InetSocketAddress customProxy = new InetSocketAddress("10.0.0.1", 8080);
        ProxySource mockSource = new ProxySource() {
            @Override
            public String name() {
                return "mock";
            }

            @Override
            public List<InetSocketAddress> fetch() {
                return List.of(customProxy);
            }
        };

        MultiSourceProxyPool pool = new MultiSourceProxyPool(mockSource);
        assertEquals(1, pool.getSources().size());
        assertEquals(0, pool.size());

        pool.refreshPool();
        assertEquals(1, pool.size());
        assertEquals(customProxy, pool.getNextCandidate());
    }
}
