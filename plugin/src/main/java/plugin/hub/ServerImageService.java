package plugin.hub;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import arc.util.Log;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import common.server.Server;
import mindustry.Vars;
import mindustry.gen.Player;
import plugin.Tasks;
import plugin.annotations.Component;
import plugin.utils.HttpClients;

@Component
public class ServerImageService {

    private static final String DEFAULT_IMAGE_BASE_URL = "https://api.mindustry-tool.com/api/v4/servers/";

    private final HttpClient httpClient;
    private final String imageBaseUrl;
    private final Cache<String, Optional<byte[]>> imageCache;

    public ServerImageService() {
        this(HttpClients.shared(), DEFAULT_IMAGE_BASE_URL, null);
    }

    public ServerImageService(HttpClient httpClient, String imageBaseUrl) {
        this(httpClient, imageBaseUrl, null);
    }

    public ServerImageService(HttpClient httpClient, String imageBaseUrl, Ticker ticker) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.imageBaseUrl = imageBaseUrl.endsWith("/") ? imageBaseUrl : imageBaseUrl + "/";
        var builder = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(5))
                .maximumSize(200);
        if (ticker != null) {
            builder.ticker(ticker);
        }
        this.imageCache = builder.build();
    }

    public byte[] getImage(String serverId) {
        if (serverId == null || serverId.isBlank()) {
            return null;
        }

        Optional<byte[]> cached = imageCache.getIfPresent(serverId);
        if (cached != null) {
            return cached.orElse(null);
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(imageBaseUrl + serverId + "/image"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            int status = response.statusCode();

            if (status == 200) {
                byte[] body = response.body();
                imageCache.put(serverId, Optional.ofNullable(body));
                return body;
            } else if (status == 404) {
                imageCache.put(serverId, Optional.empty());
                return null;
            } else {
                Log.warn("Unexpected status code " + status + " while fetching server image for " + serverId);
                return null;
            }
        } catch (Exception e) {
            Log.err("Failed to fetch image for server " + serverId, e);
            return null;
        }
    }

    public void streamServerTextures(Player player, Iterable<Server> servers) {
        if (player == null || player.con == null || Vars.netServer == null || servers == null) {
            return;
        }

        Tasks.io("stream-server-textures", () -> {
            for (Server server : servers) {
                if (server == null || server.getId() == null) {
                    continue;
                }
                String serverId = server.getId().toString();
                byte[] imageBytes = getImage(serverId);
                if (imageBytes != null && imageBytes.length > 0 && player.con != null && Vars.netServer != null) {
                    Vars.netServer.sendTexture(player.con, "server-" + serverId, imageBytes);
                }
            }
        });
    }

    public boolean isCached(String serverId) {
        return imageCache.getIfPresent(serverId) != null;
    }

    public void clearCache() {
        imageCache.invalidateAll();
    }
}
