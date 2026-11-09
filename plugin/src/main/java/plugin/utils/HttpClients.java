package plugin.utils;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Centralized provider for the plugin's shared {@link HttpClient}.
 * Prevents thread exhaustion and optimizes connection pooling.
 * <p>
 * The SSE client in {@code PlayerConnectService} is intentionally not shared:
 * it holds a long-lived streaming connection. {@code WsClient} in {@code :gateway}
 * keeps its own client for websocket dialing.
 */
public final class HttpClients {

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private static final HttpClient DEFAULT_CLIENT = HttpClient.newBuilder()
            .connectTimeout(DEFAULT_CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private HttpClients() {}

    /**
     * @return Shared, long-lived thread-safe HttpClient singleton with default version (H2 for https, H1 for http).
     */
    public static HttpClient shared() {
        return DEFAULT_CLIENT;
    }
}
