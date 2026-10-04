package server.service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import arc.util.Log;
import dto.PluginQueryDto;
import dto.PluginVersionDto;
import server.utils.ApiError;
import server.utils.HttpClients;
import server.utils.Utils;

public class PluginProxyService {

    private static final String PLUGIN_API_URL = "https://api.mindustry-tool.com/api/v4/plugins";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient httpClient;
    private final Cache<String, PluginVersionDto> versionCache;
    private final Cache<String, byte[]> binaryCache;

    public PluginProxyService() {
        this(
                HttpClients.shared(),
                Caffeine.newBuilder()
                        .expireAfterWrite(5, TimeUnit.MINUTES)
                        .maximumSize(500)
                        .build(),
                Caffeine.newBuilder()
                        .expireAfterWrite(5, TimeUnit.MINUTES)
                        .maximumSize(100)
                        .build()
        );
    }

    public PluginProxyService(HttpClient httpClient, Cache<String, PluginVersionDto> versionCache, Cache<String, byte[]> binaryCache) {
        this.httpClient = httpClient;
        this.versionCache = versionCache;
        this.binaryCache = binaryCache;
    }

    public static String buildCacheKey(PluginQueryDto query) {
        if (query == null) {
            return "";
        }
        return query.getOwner() + "/" + query.getRepo() + ":" + query.getTag();
    }

    public PluginVersionDto getPluginVersion(PluginQueryDto query) {
        if (query == null || query.getRepo() == null || query.getOwner() == null || query.getTag() == null) {
            throw new ApiError(400, "Missing required query parameters: owner, repo, tag");
        }

        String key = buildCacheKey(query);
        return versionCache.get(key, k -> fetchVersionFromUpstream(query));
    }

    public byte[] downloadPlugin(PluginQueryDto query) {
        if (query == null || query.getRepo() == null || query.getOwner() == null || query.getTag() == null) {
            throw new ApiError(400, "Missing required query parameters: owner, repo, tag");
        }

        String key = buildCacheKey(query);
        return binaryCache.get(key, k -> fetchBinaryFromUpstream(query));
    }

    private PluginVersionDto fetchVersionFromUpstream(PluginQueryDto query) {
        String uriString = PLUGIN_API_URL + "/version?repo=" + urlEncode(query.getRepo())
                + "&owner=" + urlEncode(query.getOwner())
                + "&tag=" + urlEncode(query.getTag());

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(uriString))
                    .timeout(HTTP_TIMEOUT)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new ApiError(response.statusCode(), "Upstream plugin version error: " + response.body());
            }

            return Utils.readJsonAsClass(response.body(), PluginVersionDto.class);
        } catch (ApiError e) {
            throw e;
        } catch (Exception e) {
            Log.err("Failed to fetch plugin version from upstream: " + uriString, e);
            throw new RuntimeException("Failed to fetch plugin version: " + e.getMessage(), e);
        }
    }

    private byte[] fetchBinaryFromUpstream(PluginQueryDto query) {
        String uriString = PLUGIN_API_URL + "/download?repo=" + urlEncode(query.getRepo())
                + "&owner=" + urlEncode(query.getOwner())
                + "&tag=" + urlEncode(query.getTag());

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(uriString))
                    .timeout(Duration.ofMinutes(2))
                    .GET()
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new ApiError(response.statusCode(), "Upstream plugin download error code: " + response.statusCode());
            }

            return response.body();
        } catch (ApiError e) {
            throw e;
        } catch (Exception e) {
            Log.err("Failed to download plugin binary from upstream: " + uriString, e);
            throw new RuntimeException("Failed to download plugin binary: " + e.getMessage(), e);
        }
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
