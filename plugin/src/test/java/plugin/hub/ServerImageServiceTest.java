package plugin.hub;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import com.github.benmanes.caffeine.cache.Ticker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerImageServiceTest {

    private static class StubResponse<T> implements HttpResponse<T> {
        private final int statusCode;
        private final T body;

        StubResponse(int statusCode, T body) {
            this.statusCode = statusCode;
            this.body = body;
        }

        @Override public int statusCode() { return statusCode; }
        @Override public HttpRequest request() { return null; }
        @Override public Optional<HttpResponse<T>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers() { return HttpHeaders.of(Collections.emptyMap(), (k, v) -> true); }
        @Override public T body() { return body; }
        @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
        @Override public URI uri() { return URI.create("http://localhost"); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_2; }
    }

    private static class CountingHttpClient extends HttpClient {
        private final AtomicInteger requestCount = new AtomicInteger();
        private final HttpResponse<byte[]> response;

        CountingHttpClient(HttpResponse<byte[]> response) {
            this.response = response;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseHandler) throws IOException, InterruptedException {
            requestCount.incrementAndGet();
            return (HttpResponse<T>) response;
        }

        @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<Duration> connectTimeout() { return Optional.empty(); }
        @Override public HttpClient.Redirect followRedirects() { return HttpClient.Redirect.NEVER; }
        @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
        @Override public SSLContext sslContext() { return null; }
        @Override public SSLParameters sslParameters() { return null; }
        @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_2; }
        @Override public Optional<Executor> executor() { return Optional.empty(); }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseHandler) { throw new UnsupportedOperationException(); }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseHandler, HttpResponse.PushPromiseHandler<T> pushPromiseHandler) { throw new UnsupportedOperationException(); }
    }

    @Test
    void testSuccessfulFetchAndCacheHit() {
        byte[] payload = new byte[]{10, 20, 30};
        CountingHttpClient client = new CountingHttpClient(new StubResponse<>(200, payload));
        ServerImageService service = new ServerImageService(client, "https://api.mindustry-tool.com/api/v4/servers/");

        byte[] first = service.getImage("srv-1");
        assertArrayEquals(payload, first);
        assertEquals(1, client.requestCount.get());
        assertTrue(service.isCached("srv-1"));

        byte[] second = service.getImage("srv-1");
        assertArrayEquals(payload, second);
        assertEquals(1, client.requestCount.get(), "Second call should hit memory cache without sending HTTP request");
    }

    @Test
    void testNegativeCachingOn404() {
        CountingHttpClient client = new CountingHttpClient(new StubResponse<>(404, new byte[0]));
        ServerImageService service = new ServerImageService(client, "https://api.mindustry-tool.com/api/v4/servers/");

        byte[] first = service.getImage("srv-missing");
        assertNull(first);
        assertEquals(1, client.requestCount.get());
        assertTrue(service.isCached("srv-missing"), "Negative 404 response should be cached");

        byte[] second = service.getImage("srv-missing");
        assertNull(second);
        assertEquals(1, client.requestCount.get(), "Subsequent call during TTL should return cached null without sending HTTP request");
    }

    @Test
    void testCacheExpirationAfter5Minutes() {
        byte[] payload = new byte[]{1, 2, 3};
        CountingHttpClient client = new CountingHttpClient(new StubResponse<>(200, payload));
        AtomicLong nanos = new AtomicLong(0L);
        Ticker ticker = nanos::get;

        ServerImageService service = new ServerImageService(client, "https://api.mindustry-tool.com/api/v4/servers/", ticker);

        service.getImage("srv-expire");
        assertEquals(1, client.requestCount.get());

        // Advance 4 minutes (under 5 minute TTL)
        nanos.addAndGet(Duration.ofMinutes(4).toNanos());
        service.getImage("srv-expire");
        assertEquals(1, client.requestCount.get());

        // Advance 2 more minutes (total 6 minutes, exceeds 5 minute TTL)
        nanos.addAndGet(Duration.ofMinutes(2).toNanos());
        service.getImage("srv-expire");
        assertEquals(2, client.requestCount.get(), "Expired entry should trigger a new HTTP request");
    }

    @Test
    void testBlankOrNullIdReturnsNullWithoutHttpRequest() {
        CountingHttpClient client = new CountingHttpClient(new StubResponse<>(200, new byte[]{1}));
        ServerImageService service = new ServerImageService(client, "https://api.mindustry-tool.com/api/v4/servers/");

        assertNull(service.getImage(null));
        assertNull(service.getImage("   "));
        assertEquals(0, client.requestCount.get());
    }
}
