package server.service.proxy;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ProxyScrapeSource implements ProxySource {
    public static final String URL =
            "https://api.proxyscrape.com/v2/?request=displayproxies&protocol=http&timeout=5000&country=all&ssl=yes&anonymity=all";
    private static final Duration TIMEOUT = Duration.ofSeconds(8);

    private final HttpClient httpClient;

    public ProxyScrapeSource() {
        this(HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
    }

    public ProxyScrapeSource(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public String name() {
        return "proxyscrape";
    }

    @Override
    public List<InetSocketAddress> fetch() throws Exception {
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(URL)).timeout(TIMEOUT).GET().build();
        HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            throw new RuntimeException("ProxyScrape returned HTTP " + res.statusCode());
        }
        return parsePlainText(res.body());
    }

    public static List<InetSocketAddress> parsePlainText(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }
        List<InetSocketAddress> list = new ArrayList<>();
        String[] lines = text.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            String[] parts = trimmed.split(":");
            if (parts.length >= 2) {
                try {
                    String host = parts[0].trim();
                    int port = Integer.parseInt(parts[1].trim());
                    if (port > 0 && port <= 65535) {
                        list.add(new InetSocketAddress(host, port));
                    }
                } catch (NumberFormatException ignored) {}
            }
        }
        return list;
    }
}
