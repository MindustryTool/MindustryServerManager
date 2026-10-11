package server.service.translation;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import server.service.translation.provider.BingWebProvider;
import server.service.translation.provider.GoogleWebProvider;
import server.service.translation.provider.LingvaProvider;
import server.service.translation.provider.LlamaTranslationProvider;

import static org.junit.jupiter.api.Assertions.*;

public class ProviderExceptionMappingTest {

    private static HttpClient stubClient(int status, String body) {
        return new FakeClient((req, handler) -> FakeResponse.of(status, body));
    }

    private static HttpClient failingClient(IOException failure) {
        return new FakeClient((req, handler) -> {
            throw failure;
        });
    }

    @FunctionalInterface
    interface SendHandler {
        HttpResponse<String> send(HttpRequest req, HttpResponse.BodyHandler<String> handler) throws Exception;
    }

    static class FakeClient extends HttpClient {
        private final SendHandler handler;

        FakeClient(SendHandler handler) {
            this.handler = handler;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler) throws IOException, InterruptedException {
            try {
                return (HttpResponse<T>) handler.send(request, (HttpResponse.BodyHandler<String>) responseBodyHandler);
            } catch (IOException | InterruptedException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException(e);
            }
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            throw new UnsupportedOperationException();
        }

        @Override
        public SSLParameters sslParameters() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }
    }

    static class FakeResponse implements HttpResponse<String> {
        private final int status;
        private final String body;

        private FakeResponse(int status, String body) {
            this.status = status;
            this.body = body;
        }

        static FakeResponse of(int status, String body) {
            return new FakeResponse(status, body);
        }

        @Override
        public int statusCode() {
            return status;
        }

        @Override
        public HttpRequest request() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<HttpResponse<String>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public java.net.http.HttpHeaders headers() {
            return java.net.http.HttpHeaders.of(java.util.Map.of(), (a, b) -> true);
        }

        @Override
        public String body() {
            return body;
        }

        @Override
        public Optional<javax.net.ssl.SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public java.net.URI uri() {
            return java.net.URI.create("http://localhost/");
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }

    @Test
    public void testBingStatusMapping() {
        assertInstanceOf(TranslationException.Temporary.class,
                TranslationException.fromHttpStatus("bing-web", 429, "slow"));
        assertInstanceOf(TranslationException.Permanent.class,
                TranslationException.fromHttpStatus("bing-web", 400, "bad"));
    }

    @Test
    public void testBingMalformedJsonToPermanent() {
        BingWebProvider p = new BingWebProvider();
        assertThrows(TranslationException.Permanent.class, () -> p.parseResponse("not-json{{"));
    }

    @Test
    public void testGoogleMaps429ToTemporary() {
        GoogleWebProvider p = new GoogleWebProvider("google-web", null, stubClient(429, "slow"),
                new ObjectMapper());
        assertThrows(TranslationException.Temporary.class, () -> p.translate("hello", "vi"));
    }

    @Test
    public void testGoogleMalformedJsonToPermanent() {
        GoogleWebProvider p = new GoogleWebProvider();
        assertThrows(TranslationException.Permanent.class, () -> p.parseResponse("not-json"));
    }

    @Test
    public void testLingvaNon200MapsToPermanent() {
        LingvaProvider p = new LingvaProvider(stubClient(400, "bad"), new ObjectMapper());
        assertThrows(TranslationException.Permanent.class, () -> p.translate("hello", "xx-bad"));
    }

    @Test
    public void testLingva429ToTemporary() {
        LingvaProvider p = new LingvaProvider(stubClient(429, "slow"), new ObjectMapper());
        assertThrows(TranslationException.Temporary.class, () -> p.translate("hello", "vi"));
    }

    @Test
    public void testLlamaConnectionFailureToTemporary() {
        LlamaTranslationProvider p = new LlamaTranslationProvider("http://127.0.0.1:9/x",
                failingClient(new IOException("refused")), new ObjectMapper());
        assertThrows(TranslationException.Temporary.class, () -> p.translate("hello", "vi"));
    }

    @Test
    public void testLlamaMalformedJsonToPermanent() {
        LlamaTranslationProvider p = new LlamaTranslationProvider();
        assertThrows(TranslationException.Permanent.class, () -> p.parseResponse("{bad"));
    }
}
