package server.utils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class HttpClientsTest {

    @Test
    public void testSharedClientSingletonReuse() {
        HttpClient client1 = HttpClients.shared();
        HttpClient client2 = HttpClients.shared();

        assertNotNull(client1);
        assertSame(client1, client2, "HttpClients.shared() must return the same singleton instance");
        assertEquals(HttpClient.Redirect.NORMAL, client1.followRedirects());
        assertTrue(client1.connectTimeout().isPresent());
    }

    @Test
    public void testHttp11ClientSingleton() {
        HttpClient client1 = HttpClients.http11();
        HttpClient client2 = HttpClients.http11();

        assertNotNull(client1);
        assertSame(client1, client2, "HttpClients.http11() must return the same singleton instance");
        assertEquals(HttpClient.Version.HTTP_1_1, client1.version());
    }

    @Test
    public void testCreateProxiedClient() {
        ProxySelector dummySelector = new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                return List.of(new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", 8080)));
            }

            @Override
            public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {}
        };

        HttpClient proxied = HttpClients.createProxied(dummySelector, Duration.ofSeconds(4));

        assertNotNull(proxied);
        assertTrue(proxied.proxy().isPresent());
        assertSame(dummySelector, proxied.proxy().get());
        assertTrue(proxied.connectTimeout().isPresent());
        assertEquals(Duration.ofSeconds(4), proxied.connectTimeout().get());
    }

    @Test
    public void testForUriSchemeSelection() {
        HttpClient httpClient = HttpClients.forUri(URI.create("http://example.com/"));
        assertSame(HttpClients.http11(), httpClient, "http scheme must use HTTP/1.1 client");

        HttpClient httpsClient = HttpClients.forUri(URI.create("https://example.com/"));
        assertSame(HttpClients.shared(), httpsClient, "https scheme must use default client");
    }

    @Test
    public void testForUrlSchemeSelection() {
        HttpClient httpClient = HttpClients.forUrl("http://example.com/");
        assertSame(HttpClients.http11(), httpClient, "http URL must use HTTP/1.1 client");

        HttpClient httpsClient = HttpClients.forUrl("https://example.com/");
        assertSame(HttpClients.shared(), httpsClient, "https URL must use default client");
    }
}
