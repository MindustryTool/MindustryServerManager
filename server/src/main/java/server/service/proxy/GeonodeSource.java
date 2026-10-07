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

import arc.util.Log;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import server.utils.HttpClients;

public class GeonodeSource implements ProxySource {
    public static final String URL =
            "https://proxylist.geonode.com/api/proxy-list?protocols=http%2Chttps&limit=50&page=1&sort_by=lastChecked&sort_type=desc";
    private static final Duration TIMEOUT = Duration.ofSeconds(8);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public GeonodeSource() {
        this(HttpClients.shared(), new ObjectMapper());
    }

    public GeonodeSource(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "geonode";
    }

    @Override
    public List<InetSocketAddress> fetch() throws Exception {
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(URL)).timeout(TIMEOUT).GET().build();
        HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            throw new RuntimeException("Geonode returned HTTP " + res.statusCode());
        }
        
        return parseJson(res.body(), objectMapper);
    }

    public static List<InetSocketAddress> parseJson(String json, ObjectMapper mapper) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        List<InetSocketAddress> list = new ArrayList<>();
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (Exception e) {
            Log.warn("Geonode: failed to parse response JSON: @", e.getMessage());
            return list;
        }

        JsonNode data = root.get("data");
        if (data == null || !data.isArray()) {
            Log.warn("Geonode: response missing 'data' array");
            return list;
        }

        for (JsonNode node : data) {
            JsonNode ipNode = node.get("ip");
            JsonNode portNode = node.get("port");
            if (ipNode == null || portNode == null) {
                Log.warn("Geonode: ignoring entry missing ip/port: @", node);
                continue;
            }
            String ip = ipNode.asText().trim();
            if (ip.isEmpty()) {
                Log.warn("Geonode: ignoring entry with empty ip: @", node);
                continue;
            }
            int port = portNode.asInt();
            if (port <= 0 || port > 65535) {
                Log.warn("Geonode: ignoring entry with out-of-range port: @", node);
                continue;
            }
            list.add(new InetSocketAddress(ip, port));
        }

        return list;
    }
}
