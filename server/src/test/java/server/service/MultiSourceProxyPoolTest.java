package server.service;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
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

    @Test
    public void testAsProxySelectorSelectAndEvict() {
        MultiSourceProxyPool pool = new MultiSourceProxyPool();
        InetSocketAddress p1 = new InetSocketAddress("1.1.1.1", 8080);
        pool.addProxies(List.of(p1));
        assertEquals(1, pool.size());

        ProxySelector selector = pool.asProxySelector();
        List<Proxy> proxies = selector.select(URI.create("https://translate.googleapis.com"));
        assertEquals(1, proxies.size());
        assertEquals(Proxy.Type.HTTP, proxies.get(0).type());
        assertEquals(p1, proxies.get(0).address());

        // Trigger connection failure callback
        selector.connectFailed(URI.create("https://translate.googleapis.com"), p1, new IOException("Connection refused"));
        assertEquals(0, pool.size(), "Proxy should be evicted after connectFailed");

        // When pool empty, Proxy.NO_PROXY is returned
        List<Proxy> fallback = selector.select(URI.create("https://translate.googleapis.com"));
        assertEquals(1, fallback.size());
        assertEquals(Proxy.NO_PROXY, fallback.get(0));
    }
}
