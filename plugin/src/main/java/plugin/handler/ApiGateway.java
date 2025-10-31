package plugin.handler;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import arc.util.Http;
import arc.util.Http.HttpRequest;
import arc.util.Http.HttpStatusException;
import arc.util.Log;
import arc.util.Strings;
import plugin.utils.JsonUtils;
import plugin.ServerController;
import dto.MindustryPlayerDto;
import plugin.type.PaginationRequest;
import dto.PlayerDto;
import dto.ServerDto;

public class ApiGateway {

    private static final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public static Cache<PaginationRequest, List<ServerDto>> serverQueryCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(15))
            .maximumSize(10)
            .build();

    public static void init() {
        Log.info("Setup api gateway");
        Log.info("Setup api gateway done");

    }

    public static void unload() {
        serverQueryCache.invalidateAll();
        serverQueryCache = null;

        Log.info("Api gateway unloaded");
    }

    private static String uri(String... path) {
        URI uri = URI.create("http://server-manager:8088/internal-api/v1/" + Strings.join("/", path));
        Log.debug("[REQUEST]: " + uri);

        return uri.toString();
    }

    private static HttpRequest get(String... path) {
        return Http.get(uri(path));
    }

    private static HttpRequest post(String... path) {
        return Http.post(uri(path));
    }

    private static <T> T send(HttpRequest req, Class<T> clazz) {
        return send(req, 2000, clazz);
    }

    @SuppressWarnings("unchecked")
    private static <T> T send(HttpRequest req, int timeoutMilis, Class<T> clazz) {
        String response = send(req, timeoutMilis);

        if (clazz.equals(Void.class)) {
            return null;
        }

        if (clazz.equals(String.class)) {
            return (T) response;
        }

        return JsonUtils.readJsonAsClass(response, clazz);

    }

    private static <T> List<T> sendList(HttpRequest req, int timeoutMilis, Class<T> clazz) {
        String response = send(req, timeoutMilis);
        return JsonUtils.readJsonAsArrayClass(response, clazz);
    }

    private static String send(HttpRequest req, int timeoutMilis) {
        CompletableFuture<String> res = new CompletableFuture<>();
        req
                .header("X-SERVER-ID", ServerController.SERVER_ID.toString())
                .timeout(timeoutMilis)
                .redirects(true)
                .error(error -> {
                    if (error instanceof HttpStatusException) {
                        HttpStatusException e = (HttpStatusException) error;
                        res.completeExceptionally(
                                new RuntimeException(req.url + " " + e.response.getResultAsString(), e));
                    } else {
                        res.completeExceptionally(new RuntimeException(req.url, error));
                    }
                })
                .submit(response -> {
                    res.complete(response.getResultAsString());
                });
        try {
            return res.get(timeoutMilis, TimeUnit.MILLISECONDS);
        } catch (Throwable e) {
            throw new RuntimeException(req.method + " " + req.url, e);
        }
    }

    public static MindustryPlayerDto setPlayer(PlayerDto payload) {
        try {
            return send((post("players"))
                    .header("Content-Type", "application/json")//
                    .content(JsonUtils.toJsonString(payload)), MindustryPlayerDto.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void sendPlayerLeave(PlayerDto payload) {
        try {
          
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    public static int getTotalPlayer() {
        try {
            return send(get("total-player"), Integer.class);
        } catch (Exception e) {
            e.printStackTrace();
            return 0;
        }

    }

    public static void sendChatMessage(String chat) {
        try {
           
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    public static String host(String targetServerId) {
        Object lock = locks.computeIfAbsent(targetServerId, k -> new Object());

        synchronized (lock) {
            Log.info("Hosting server: " + targetServerId);
            try {
                return send(post("host")
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
                return sendList(
                        get(String.format("servers?page=%s&size=%s", request.getPage(), request.getSize())),
                        2000,
                        ServerDto.class);
            } catch (Exception e) {
                e.printStackTrace();
                return new ArrayList<>();
            }
        });
    }

    public static String translate(String text, String targetLanguage) {
        try {
            return send(post(String.format("translate/%s", targetLanguage))
                    .header("Content-Type", "text/plain")//
                    .content(text), String.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
