package plugin.utils;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpUtilsTest {

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

    private static class StubHttpClient extends HttpClient {
        private final HttpResponse<byte[]> response;
        private final IOException failure;
        final AtomicReference<HttpRequest> lastRequest = new AtomicReference<>();

        StubHttpClient(HttpResponse<byte[]> response) {
            this.response = response;
            this.failure = null;
        }

        StubHttpClient(IOException failure) {
            this.response = null;
            this.failure = failure;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseHandler) throws IOException, InterruptedException {
            lastRequest.set(request);
            if (failure != null) {
                throw failure;
            }
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
    void testGetJoinsPathAndQuery() throws IOException, InterruptedException {
        StubHttpClient client = new StubHttpClient(new StubResponse<>(200, "ok".getBytes(StandardCharsets.UTF_8)));
        byte[] body = HttpUtils.send(client, HttpUtils.get("https://api.example.com", "servers?page=1&size=20"), Duration.ofSeconds(5));
        assertEquals("ok", new String(body, StandardCharsets.UTF_8));
        assertEquals("https://api.example.com/servers?page=1&size=20", client.lastRequest.get().uri().toString());
        assertEquals("GET", client.lastRequest.get().method());
        assertEquals(Duration.ofSeconds(5), client.lastRequest.get().timeout().get());
    }

    @Test
    void testPostSetsBodyHeaders() throws IOException, InterruptedException {
        StubHttpClient client = new StubHttpClient(new StubResponse<>(200, new byte[0]));
        byte[] body = HttpUtils.send(client,
                HttpUtils.post("https://api.example.com", "api", "v4", "maps", "image-json")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"data\":\"x\"}")),
                Duration.ofSeconds(180));
        assertEquals(0, body.length);
        assertEquals("https://api.example.com/api/v4/maps/image-json", client.lastRequest.get().uri().toString());
        assertEquals("POST", client.lastRequest.get().method());
        assertEquals("application/json", client.lastRequest.get().headers().firstValue("Content-Type").get());
        assertEquals(Duration.ofSeconds(180), client.lastRequest.get().timeout().get());
    }

    @Test
    void test4xxThrowsWithBody() {
        StubHttpClient client = new StubHttpClient(new StubResponse<>(404, "Not Found".getBytes(StandardCharsets.UTF_8)));
        RuntimeException ex = assertThrows(RuntimeException.class, () ->
                HttpUtils.send(client, HttpUtils.get("https://api.example.com", "missing"), Duration.ofSeconds(5)));
        assertTrue(ex.getMessage().contains("https://api.example.com/missing"));
        assertTrue(ex.getMessage().contains("Not Found"));
    }

    @Test
    void testTimeoutMapsToRuntimeException() {
        StubHttpClient client = new StubHttpClient(new IOException("timed out"));
        RuntimeException ex = assertThrows(RuntimeException.class, () ->
                HttpUtils.send(client, HttpUtils.get("https://api.example.com", "slow"), Duration.ofSeconds(5)));
        assertTrue(ex.getMessage().contains("https://api.example.com/slow"));
    }
}
