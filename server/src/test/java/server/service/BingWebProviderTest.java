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
import server.service.translation.provider.BingWebProvider;

public class BingWebProviderTest {

    @Test
    public void testMetadata() {
        BingWebProvider provider = new BingWebProvider();
        assertEquals("bing-web", provider.name());
        assertTrue(provider.isAvailable());
    }

    @Test
    public void testExtractCredentials() {
        BingWebProvider provider = new BingWebProvider();
        String html = """
                <html>
                <head>
                <script>
                var IG="SAMPLE_IG_12345";
                </script>
                </head>
                <body data-iid="translator.5023">
                <script>
                params_AbusePreventionHelper = [1791541570891,"sample_token_abc",3600000];
                </script>
                </body>
                </html>
                """;

        BingWebProvider.SessionCredentials creds = provider.extractCredentials(html);
        assertNotNull(creds);
        assertEquals("SAMPLE_IG_12345", creds.ig());
        assertEquals("translator.5023", creds.iid());
        assertEquals("1791541570891", creds.key());
        assertEquals("sample_token_abc", creds.token());
        assertFalse(creds.isExpired());
    }

    @Test
    public void testParseSuccessfulResponse() throws Exception {
        BingWebProvider provider = new BingWebProvider();
        String json = """
                [{"translations":[{"text":"Xin ch&#224;o th&#7871; gi&#7899;i!","to":"vi"}],"usedLLM":true,"detectedLanguage":{"language":"en"}}]
                """;

        TranslationResponse response = provider.parseResponse(json);
        assertNotNull(response);
        assertEquals("Xin chào thế giới!", response.getTranslatedText());
        assertEquals("en", response.getSourceLanguage());
    }

    @Test
    public void testParseExpiredResponseThrowsSessionExpiredException() {
        BingWebProvider provider = new BingWebProvider();
        String json = "{\"statusCode\":205,\"errorMessage\":\"\"}";

        assertThrows(BingWebProvider.SessionExpiredException.class, () -> {
            provider.parseResponse(json);
        });
    }

    @Test
    public void testParseInvalidFormatThrowsException() {
        BingWebProvider provider = new BingWebProvider();
        assertThrows(IllegalArgumentException.class, () -> {
            provider.parseResponse("[]");
        });
        assertThrows(IllegalArgumentException.class, () -> {
            provider.parseResponse("[{\"translations\":[]}]");
        });
    }

    @Test
    public void testHtmlEntityUnescaping() {
        assertEquals("It's fine", BingWebProvider.unescapeHtml("It&#39;s fine"));
        assertEquals("He said \"yes\" & 'no'", BingWebProvider.unescapeHtml("He said &quot;yes&quot; &amp; &#39;no&#39;"));
        assertEquals("<tag> & test", BingWebProvider.unescapeHtml("&lt;tag&gt; &amp; test"));
        assertEquals("Hex ' apostrophe", BingWebProvider.unescapeHtml("Hex &#x27; apostrophe"));
        assertEquals("Normal text without entities", BingWebProvider.unescapeHtml("Normal text without entities"));
    }

    @Test
    public void testTranslateBlankReturnsNull() throws Exception {
        BingWebProvider provider = new BingWebProvider();
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

    private static class SequentialHttpClient extends HttpClient {
        private final HttpResponse<String>[] responses;
        private int index = 0;

        @SafeVarargs
        SequentialHttpClient(HttpResponse<String>... responses) {
            this.responses = responses;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseHandler) throws IOException, InterruptedException {
            if (index >= responses.length) {
                throw new IllegalStateException("Exhausted stub responses");
            }
            return (HttpResponse<T>) responses[index++];
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
    public void testSessionRefreshOnExpiryRetry() throws Exception {
        HttpResponse<String> htmlResponse = new StubHttpResponse<>(200, """
                IG:"IG_1" data-iid="IID_1"
                params_AbusePreventionHelper = [123,"tok",3600000]
                """);

        HttpResponse<String> expiredResponse = new StubHttpResponse<>(200, "{\"statusCode\":205}");

        HttpResponse<String> successResponse = new StubHttpResponse<>(200, """
                [{"translations":[{"text":"Translated text","to":"vi"}],"detectedLanguage":{"language":"en"}}]
                """);

        // 1: initial html fetch
        // 2: translate POST -> 205 (session expired)
        // 3: refresh html fetch
        // 4: translate POST -> 200 success
        HttpClient client = new SequentialHttpClient(htmlResponse, expiredResponse, htmlResponse, successResponse);

        BingWebProvider provider = new BingWebProvider(client, new ObjectMapper());
        TranslationResponse result = provider.translate("Hello", "vi");

        assertNotNull(result);
        assertEquals("Translated text", result.getTranslatedText());
        assertEquals("en", result.getSourceLanguage());
    }
}
