package server.utils;

import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Centralized provider for long-lived {@link HttpClient} instances.
 * Prevents thread exhaustion and optimizes connection pooling.
 */
public final class HttpClients {

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);

    private static final HttpClient DEFAULT_CLIENT = HttpClient.newBuilder()
            .connectTimeout(DEFAULT_CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    private HttpClients() {}

    /**
     * @return Shared, long-lived thread-safe HttpClient singleton with default version (H2 for https, H1 for http).
     */
    public static HttpClient shared() {
        return DEFAULT_CLIENT;
    }

    /**
     * Creates a long-lived proxied HttpClient with the provided dynamic ProxySelector and timeout.
     *
     * @param proxySelector Dynamic ProxySelector delegating to an active proxy pool.
     * @param connectTimeout Connection establishment timeout.
     * @return Configured HttpClient.
     */
    public static HttpClient createProxied(ProxySelector proxySelector, Duration connectTimeout) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL);

        if (connectTimeout != null) {
            builder.connectTimeout(connectTimeout);
        } else {
            builder.connectTimeout(DEFAULT_CONNECT_TIMEOUT);
        }

        if (proxySelector != null) {
            builder.proxy(proxySelector);
        }

        return builder.build();
    }
}
