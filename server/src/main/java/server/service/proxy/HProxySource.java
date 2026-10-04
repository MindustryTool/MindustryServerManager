package server.service.proxy;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

public class HProxySource implements ProxySource {
    public static final String URL =
            "https://raw.githubusercontent.com/hproxy-com/free-proxy-list/main/http.txt";
    private static final Duration TIMEOUT = Duration.ofSeconds(8);

    private final HttpClient httpClient;

    public HProxySource() {
        this(HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
    }

    public HProxySource(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public String name() {
        return "hproxy";
    }

    @Override
    public List<InetSocketAddress> fetch() throws Exception {
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(URL)).timeout(TIMEOUT).GET().build();
        HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            throw new RuntimeException("HProxy returned HTTP " + res.statusCode());
        }
        return ProxyScrapeSource.parsePlainText(res.body());
    }
}
