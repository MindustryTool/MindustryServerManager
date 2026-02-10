package plugin.service;

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
import plugin.annotations.Component;
import plugin.annotations.Destroy;
import plugin.utils.HttpUtils;
import plugin.utils.JsonUtils;
import plugin.utils.Utils;
import plugin.Control;
import plugin.type.PaginationRequest;
import plugin.type.TranslationDto;
import dto.LoginDto;
import dto.LoginRequestDto;
import dto.ServerDto;
import mindustry.gen.Player;

@Component
public class ApiGateway {

    private final String GATEWAY_URL = "http://server-manager-v2:8088/gateway/v2";
    private final String API_URL = "https://api.mindustry-tool.com/api/v4/";
    private final String SERVER_ID = Control.SERVER_ID.toString();

    private Cache<PaginationRequest, List<ServerDto>> serverQueryCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(15))
            .maximumSize(10)
            .build();

    private final Cache<String, String> translationCache = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(30))
            .build();

    private final Cache<String, Boolean> failedTranslationCache = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(1))
            .build();

    public void requestConnection() {
        HttpUtils.get(HttpUtils.get(GATEWAY_URL, "servers", SERVER_ID, "request-connection"));
    }

    public int getTotalPlayer() {
        try {
            return HttpUtils.send(HttpUtils.get(GATEWAY_URL, "total-player"), Integer.class);
        } catch (Exception e) {
            e.printStackTrace();
            return 0;
        }
    }

    public LoginDto login(Player player) {
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

    public String host(String targetServerId) {
        Object lock = Utils.getHostingLock(targetServerId);

        Log.info("[sky]Hosting server: " + targetServerId);

        synchronized (lock) {
            try {
                return HttpUtils.send(HttpUtils.post(GATEWAY_URL, "servers", targetServerId, "host"), 90000,
                        String.class);
            } finally {
                Utils.releaseHostingLock(targetServerId);
                Log.info("[sky]Finish hosting server: " + targetServerId);
            }
        }

    }

    public synchronized List<ServerDto> getServers(PaginationRequest request) {
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

    public TranslationDto translateRaw(Locale targetLanguage, String text) {
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

    public String translate(String text, Locale targetLanguage) {
        var languageCode = targetLanguage.getLanguage();

        if (languageCode == null || languageCode.isEmpty()) {
            languageCode = "en";
        }

        var cacheKey = String.format("%s:%s", text, languageCode);

        if (failedTranslationCache.getIfPresent(cacheKey) != null) {
            return text;
        }

        var cached = translationCache.getIfPresent(cacheKey);

        if (cached != null) {
            return cached;
        }

        try {
            var result = translateRaw(targetLanguage, text);

            translationCache.put(cacheKey, result.getTranslatedText());

            return result.getTranslatedText();
        } catch (Exception e) {
            Log.warn(
                    "[" + targetLanguage.getLanguage() + "] Failed to translate text: " + text + " to " + targetLanguage
                            + "\n" + e.getMessage());
            failedTranslationCache.put(cacheKey, true);
            return text;
        }
    }

    public Seq<String> translate(Seq<String> texts, Locale targetLanguage) {
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

            if (failedTranslationCache.getIfPresent(cacheKey) != null) {
                future.complete(text);
                continue;
            }

            if (targetLanguage.equals(Locale.ENGLISH)) {
                future.complete(text);
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
                        failedTranslationCache.put(cacheKey, true);
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
            Log.warn("[" + targetLanguage.getLanguage() + "] Failed to translate texts: " + texts + " to "
                    + targetLanguage + "\n"
                    + e.getMessage());

            return texts;
        }
    }

    @Destroy
    public void destroy() {
        serverQueryCache.invalidateAll();
        serverQueryCache = null;
    }
}
