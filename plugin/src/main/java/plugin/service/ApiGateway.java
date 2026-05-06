package plugin.service;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.databind.JsonNode;
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
import plugin.type.PaginationRequest;
import dto.LoginDto;
import dto.LoginRequestDto;
import dto.ServerDto;
import dto.WsMessage;
import lombok.RequiredArgsConstructor;
import mindustry.gen.Player;

@Component
@RequiredArgsConstructor
public class ApiGateway {

    private final String API_URL = "https://api.mindustry-tool.com/api/v4/";

    private final HttpServer httpServer;

    private Cache<PaginationRequest, List<ServerDto>> serverQueryCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(15))
            .maximumSize(10)
            .build();

    private final Cache<String, String> translationCache = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(30))
            .build();

    private final Cache<String, Boolean> failedTranslationCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(1))
            .build();

    private final Map<UUID, CompletableFuture<JsonNode>> pendingRequests = new ConcurrentHashMap<>();

    public <R> CompletableFuture<R> sendRequest(String type, Object payload, Class<R> clazz) {
        WsMessage<?> request = WsMessage.create(type).withPayload(payload);

        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pendingRequests.put(request.getId(), future);

        future.orTimeout(10, TimeUnit.SECONDS);
        future.whenComplete((_res, _err) -> pendingRequests.remove(request.getId()));

        try {
            httpServer.fire(request);
        } catch (Exception e) {
            pendingRequests.remove(request.getId());
            future.completeExceptionally(e);
        }

        return future.thenApply(r -> JsonUtils.readJsonAsClass(r, clazz));
    }

    public CompletableFuture<Void> sendRequest(String type, Object payload) {
        return sendRequest(type, payload, Void.class);
    }

    public LoginDto login(Player player) {
        var body = new LoginRequestDto()
                .setUuid(player.uuid())
                .setName(player.name())
                .setIp(player.ip());

        try {
            return sendRequest("login", body, LoginDto.class).get(5, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new RuntimeException("Login failed", e);
        }
    }

    public String host(String targetServerId) {
        Object lock = Utils.getHostingLock(targetServerId);

        synchronized (lock) {
            Log.info("[sky]Hosting server: " + targetServerId);
            try {
                return sendRequest("host", targetServerId, String.class).get(90, TimeUnit.SECONDS);
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                throw new RuntimeException("Host failed", e);
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

    public String translateRaw(Locale targetLanguage, String text) {
        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException("Text is empty");
        }

        var languageCode = targetLanguage.getLanguage();

        if (languageCode == null || languageCode.isEmpty()) {
            languageCode = "en";
        }

        HashMap<String, Object> body = new HashMap<>();

        body.put("content", text);
        body.put("target", languageCode);

        try {

            var result = HttpUtils
                    .send(HttpUtils
                            .post("https://api.mindustry-tool.com/api/v4/translations/translate")
                            .header("Content-Type", "application/json")//
                            .content(JsonUtils.toJsonString(body)), 10000, String.class);

            return result;
        } catch (Exception e) {
            throw new RuntimeException(
                    "Fail to translate: " + text + " to " + targetLanguage + ", error: " + e.getMessage());
        }
    }

    public String translate(String text, Locale targetLanguage) {
        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException("Text is empty");
        }

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

            translationCache.put(cacheKey, result);

            Log.debug("Translate: <@> to <@>, result <@>", text, targetLanguage, result);

            return result;
        } catch (Exception e) {
            Log.err("Failed to translate text [" + text + "] to [" + targetLanguage + "]", e);
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

        for (var text : texts.select(t -> !t.isEmpty())) {
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

            body.put("content", text);
            body.put("target", languageCode);

            Http.post("https://api.mindustry-tool.com/api/v4/translations/translate", JsonUtils.toJsonString(body))
                    .header("Content-Type", "application/json")//
                    .timeout(10000)
                    .error(error -> {
                        if (error instanceof SocketTimeoutException) {
                            future.completeExceptionally(
                                    new RuntimeException("Timeout while translating: " + text, error));
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
                            String translated = res.getResultAsString();

                            translationCache.put(cacheKey, translated);

                            Log.debug("Translate: <@> to <@>, result <@>", text, targetLanguage, translated);

                            future.complete(translated);
                        } catch (Exception e) {
                            future.completeExceptionally(e);
                        }
                    });
        }

        try {
            CompletableFuture.allOf(result.toArray(new CompletableFuture[texts.size])).get(10, TimeUnit.SECONDS);

            return Seq.with(result).map(r -> r.getNow("This should never happen"));
        } catch (Exception e) {

            Log.err("Failed to translate text [" + texts + "] to [" + targetLanguage + "]", e);

            return texts;
        }
    }

    @Destroy
    public void destroy() {
        serverQueryCache.invalidateAll();
        serverQueryCache = null;
    }
}
