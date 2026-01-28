package plugin.handler;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import arc.util.Log;
import plugin.utils.HttpUtils;
import plugin.utils.JsonUtils;
import plugin.ServerController;
import plugin.type.PaginationRequest;
import plugin.type.TranslationDto;
import dto.LoginDto;
import dto.LoginRequestDto;
import dto.ServerDto;
import mindustry.gen.Player;

public class ApiGateway {

    private static final String GATEWAY_URL = "http://server-manager-v2:8088/gateway/v2";
    private static final String SERVER_ID = ServerController.SERVER_ID.toString();

    private static final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    private static Cache<PaginationRequest, List<ServerDto>> serverQueryCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(15))
            .maximumSize(10)
            .build();

    private static Cache<String, String> translationCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(5))
            .maximumSize(100)
            .build();

    public static void requestConnection() {
        HttpUtils.get(HttpUtils.get(GATEWAY_URL, "servers", SERVER_ID, "request-connection"));
    }

    public static int getTotalPlayer() {
        try {
            return HttpUtils.send(HttpUtils.get(GATEWAY_URL, "total-player"), Integer.class);
        } catch (Exception e) {
            e.printStackTrace();
            return 0;
        }
    }

    public static LoginDto login(Player player) {
        var body = new LoginRequestDto()
                .setUuid(player.uuid())
                .setName(player.name())
                .setIp(player.ip());

        return HttpUtils
                .send(HttpUtils
                        .post(GATEWAY_URL, "servers", SERVER_ID, "login")
                        .header("Content-Type", "application/json")//
                        .content(JsonUtils.toJsonString(body)), 5000, LoginDto.class);

    }

    public static String host(String targetServerId) {
        Object lock = locks.computeIfAbsent(targetServerId, k -> new Object());

        synchronized (lock) {
            Log.info("Hosting server: " + targetServerId);
            try {
                return HttpUtils
                        .send(HttpUtils
                                .post(GATEWAY_URL, "host")
                                .header("Content-Type", "text/plain")//
                                .content(targetServerId), 45, String.class);
            } catch (Exception e) {
                Log.err("Error hosting server: " + targetServerId, e);
                throw new RuntimeException(e);
            } finally {
                locks.remove(targetServerId);
                Log.info("Finish hosting server: " + targetServerId);
            }
        }
    }

    public static synchronized List<ServerDto> getServers(PaginationRequest request) {
        return serverQueryCache.get(request, _ignore -> {
            try {
                return HttpUtils
                        .sendList(
                                HttpUtils.get(GATEWAY_URL,
                                        String.format("servers?page=%s&size=%s", request.getPage(), request.getSize())),
                                2000,
                                ServerDto.class);
            } catch (Exception e) {
                e.printStackTrace();
                return new ArrayList<>();
            }
        });
    }

    public static String translate(String text, Locale targetLanguage) {
        var languageCode = targetLanguage.getLanguage();

        if (languageCode == null || languageCode.isEmpty()) {
            languageCode = "en";
        }

        var cacheKey = String.format("%s:%s", text, languageCode);

        var cached = translationCache.getIfPresent(cacheKey);

        if (cached != null) {
            return cached;
        }

        HashMap<String, Object> body = new HashMap<>();

        body.put("q", text);
        body.put("source", "auto");
        body.put("target", languageCode);

        try {
            var result = HttpUtils
                    .send(HttpUtils
                            .post("https://api.mindustry-tool.com/api/v4/libre")
                            .header("Content-Type", "text/plain")//
                            .content(JsonUtils.toJsonString(body)), TranslationDto.class);

            translationCache.put(cacheKey, result.getTranslatedText());

            return result.getTranslatedText();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void init() {
        Log.info("Setup api gateway done");

    }

    public static void unload() {
        serverQueryCache.invalidateAll();
        serverQueryCache = null;

        Log.info("Api gateway unloaded");
    }
}
