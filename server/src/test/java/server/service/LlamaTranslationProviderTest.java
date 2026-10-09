package server.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collections;
import java.util.Optional;
import javax.net.ssl.SSLSession;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import common.translation.TranslationResponse;
import server.service.translation.provider.LlamaTranslationProvider;

public class LlamaTranslationProviderTest {

    @Test
    public void testMetadataAndDefaults() {
        LlamaTranslationProvider provider = new LlamaTranslationProvider();
        assertEquals("llama-local", provider.name());
        assertEquals(LlamaTranslationProvider.DEFAULT_ENDPOINT, provider.getEndpoint());
        assertTrue(provider.isAvailable());
    }

    @Test
    public void testBuildPrompt() {
        LlamaTranslationProvider provider = new LlamaTranslationProvider();
        String prompt = provider.buildPrompt("Hello world", "vi");
        assertTrue(prompt.contains("Hello world"));
        assertTrue(prompt.contains("language code 'vi'"));
        assertTrue(prompt.contains("<|im_start|>system"));
        assertTrue(prompt.contains("<|im_start|>user"));
        assertTrue(prompt.contains("<|im_start|>assistant"));
    }

    @Test
    public void testParseSuccessfulResponse() throws Exception {
        LlamaTranslationProvider provider = new LlamaTranslationProvider();
        String json = "{\"content\":\"Xin ch\\u00e0o th\\u1ebf gi\\u1edbi\"}";

        TranslationResponse response = provider.parseResponse(json);
        assertNotNull(response);
        assertEquals("Xin chào thế giới", response.getTranslatedText());
    }

    @Test
    public void testParseResponseWithSurroundingQuotes() throws Exception {
        LlamaTranslationProvider provider = new LlamaTranslationProvider();
        String json = "{\"content\":\"\\\"Defend the core\\\"\"}";

        TranslationResponse response = provider.parseResponse(json);
        assertNotNull(response);
        assertEquals("Defend the core", response.getTranslatedText());
    }

    @Test
    public void testParseEmptyOrBlankContentReturnsNull() throws Exception {
        LlamaTranslationProvider provider = new LlamaTranslationProvider();
        assertNull(provider.parseResponse("{\"content\":\"\"}"));
        assertNull(provider.parseResponse("{\"content\":\"   \"}"));
    }

    @Test
    public void testParseInvalidJsonThrows() {
        LlamaTranslationProvider provider = new LlamaTranslationProvider();
        assertThrows(IllegalArgumentException.class, () -> provider.parseResponse(""));
        assertThrows(IllegalArgumentException.class, () -> provider.parseResponse("[]"));
        assertThrows(IllegalArgumentException.class, () -> provider.parseResponse("{\"other\":123}"));
    }

    @Test
    public void testTranslateBlankReturnsNull() throws Exception {
        LlamaTranslationProvider provider = new LlamaTranslationProvider();
        assertNull(provider.translate(null, "vi"));
        assertNull(provider.translate("", "vi"));
        assertNull(provider.translate("   ", "vi"));
        assertNull(provider.translate("hello", null));
        assertNull(provider.translate("hello", ""));
    }

    private static class StubHttpResponse<T> implements HttpResponse<T> {
        private final int statusCode;
        private final T body;

        StubHttpResponse(int statusCode, T body) {
            this.statusCode = statusCode;
            this.body = body;
        }

        @Override public int statusCode() { return statusCode; }
        @Override public T body() { return body; }
        @Override public HttpRequest request() { return null; }
        @Override public Optional<HttpResponse<T>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers() { return HttpHeaders.of(Collections.emptyMap(), (k, v) -> true); }
        @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
        @Override public URI uri() { return URI.create("http://localhost"); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_2; }
    }

    private static class MockHttpClient extends HttpClient {
        private final HttpResponse<String> response;

        MockHttpClient(HttpResponse<String> response) {
            this.response = response;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseHandler) throws IOException, InterruptedException {
            return (HttpResponse<T>) response;
        }

        @Override public Optional<java.net.CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<java.time.Duration> connectTimeout() { return Optional.empty(); }
        @Override public HttpClient.Redirect followRedirects() { return HttpClient.Redirect.NEVER; }
        @Override public Optional<java.net.ProxySelector> proxy() { return Optional.empty(); }
        @Override public javax.net.ssl.SSLContext sslContext() { return null; }
        @Override public javax.net.ssl.SSLParameters sslParameters() { return null; }
        @Override public Optional<java.net.Authenticator> authenticator() { return Optional.empty(); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_2; }
        @Override public Optional<java.util.concurrent.Executor> executor() { return Optional.empty(); }
        @Override public <T> java.util.concurrent.CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseHandler) { throw new UnsupportedOperationException(); }
        @Override public <T> java.util.concurrent.CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseHandler, HttpResponse.PushPromiseHandler<T> pushPromiseHandler) { throw new UnsupportedOperationException(); }
    }

    @Test
    public void testTranslateSuccess() throws Exception {
        HttpResponse<String> httpResponse = new StubHttpResponse<>(200, "{\"content\":\"Xin chào\"}");
        HttpClient client = new MockHttpClient(httpResponse);

        LlamaTranslationProvider provider = new LlamaTranslationProvider("http://localhost:8080/completion", client, new ObjectMapper());
        TranslationResponse result = provider.translate("Hello", "vi");

        assertNotNull(result);
        assertEquals("Xin chào", result.getTranslatedText());
    }

    @Test
    public void testTranslateHttpErrorThrows() {
        HttpResponse<String> httpResponse = new StubHttpResponse<>(500, "Internal Server Error");
        HttpClient client = new MockHttpClient(httpResponse);

        LlamaTranslationProvider provider = new LlamaTranslationProvider("http://localhost:8080/completion", client, new ObjectMapper());
        assertThrows(RuntimeException.class, () -> provider.translate("Hello", "vi"));
    }
}
