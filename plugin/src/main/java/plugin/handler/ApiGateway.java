package plugin.handler;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import arc.struct.Seq;
import arc.util.Http;
import arc.util.Http.HttpStatusException;
import arc.util.Log;
import plugin.utils.HttpUtils;
import plugin.utils.JsonUtils;
import plugin.PluginEvents;
import plugin.ServerControl;
import plugin.event.PluginUnloadEvent;
import plugin.type.PaginationRequest;
import plugin.type.TranslationDto;
import dto.LoginDto;
import dto.LoginRequestDto;
import dto.ServerDto;
import mindustry.gen.Player;

public class ApiGateway {

    private static final String GATEWAY_URL = "http://server-manager-v2:8088/gateway/v2";
    private static final String API_URL = "https://api.mindustry-tool.com/api/v4/";
    private static final String SERVER_ID = ServerControl.SERVER_ID.toString();

    private static Cache<PaginationRequest, List<ServerDto>> serverQueryCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(15))
            .maximumSize(10)
            .build();

    private static Cache<String, String> translationCache = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofHours(1))
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

    public static synchronized String host(String targetServerId) {
        Log.info("Hosting server: " + targetServerId);
        return HttpUtils.send(HttpUtils.post(GATEWAY_URL, "servers", SERVER_ID, "host"), 60000, String.class);
    }

    public static synchronized List<ServerDto> getServers(PaginationRequest request) {
        return serverQueryCache.get(request, _ignore -> {
            try {
                String query = String.format("servers?page=%s&size=%s", request.getPage(), request.getSize());

                return HttpUtils.sendList(HttpUtils.get(API_URL, query), 2000, ServerDto.class);
            } catch (Exception e) {
                e.printStackTrace();
                return new ArrayList<>();
            }
        });
    }

    public static TranslationDto translateRaw(Locale targetLanguage, String text) {
        var languageCode = targetLanguage.getLanguage();

        if (languageCode == null || languageCode.isEmpty()) {
            languageCode = "en";
        }

        HashMap<String, Object> body = new HashMap<>();

        body.put("q", text);
        body.put("source", "auto");
        body.put("target", languageCode);

        var result = HttpUtils
                .send(HttpUtils
                        .post("https://api.mindustry-tool.com/api/v4/libre")
                        .header("Content-Type", "application/json")//
                        .content(JsonUtils.toJsonString(body)), 3000, TranslationDto.class);

        return result;
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

        try {
            var result = translateRaw(targetLanguage, text);

            translationCache.put(cacheKey, result.getTranslatedText());

            return result.getTranslatedText();
        } catch (Exception e) {
            Log.err("[" + targetLanguage.getLanguage() + "] Failed to translate text: " + text + "\n" + e.getMessage());
            return text;
        }
    }

    public static Seq<String> translate(Seq<String> texts, Locale targetLanguage) {
        var languageCode = targetLanguage.getLanguage();

        if (languageCode == null || languageCode.isEmpty()) {
            languageCode = "en";
        }

        List<CompletableFuture<String>> result = new ArrayList<>();

        for (var text : texts) {
            var future = new CompletableFuture<String>();

            result.add(future);

            var cacheKey = String.format("%s:%s", text, languageCode);

            var cached = translationCache.getIfPresent(cacheKey);

            if (cached != null) {
                future.complete(cached);
                continue;
            }

            HashMap<String, Object> body = new HashMap<>();

            body.put("q", text);
            body.put("source", "auto");
            body.put("target", languageCode);

            Http.post("https://api.mindustry-tool.com/api/v4/libre", JsonUtils.toJsonString(body))
                    .header("Content-Type", "application/json")//
                    .error(error -> {
                        if (error instanceof SocketTimeoutException) {
                            future.completeExceptionally(
                                    new RuntimeException("Timeout in 10s while translating: " + text, error));
                        } else if (error instanceof HttpStatusException e) {
                            future.completeExceptionally(new RuntimeException(
                                    "Error while translating: " + text + "\n" + e.response.getResultAsString(), e));
                        } else {
                            future.completeExceptionally(
                                    new RuntimeException("Error while translating: " + text, error));
                        }
                    })
                    .submit(res -> {
                        try {
                            var translated = JsonUtils.readJsonAsClass(res.getResultAsString(), TranslationDto.class)
                                    .getTranslatedText();

                            translationCache.put(cacheKey, translated);
                            future.complete(translated);
                        } catch (Throwable e) {
                            future.completeExceptionally(e);
                        }
                    });
        }

        try {
            CompletableFuture.allOf(result.toArray(new CompletableFuture[texts.size])).get(5, TimeUnit.SECONDS);

            return Seq.with(result).map(r -> r.getNow("This should never happen"));
        } catch (Exception e) {
            Log.err("[" + targetLanguage.getLanguage() + "] Failed to translate texts: " + texts + "\n"
                    + e.getMessage());
            return texts;
        }
    }

    public static void init() {
        Log.info("Setup api gateway done");

        PluginEvents.run(PluginUnloadEvent.class, ApiGateway::unload);
    }

    private static void unload() {
        serverQueryCache.invalidateAll();
        serverQueryCache = null;

        Log.info("Api gateway unloaded");
    }
}
