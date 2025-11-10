package plugin.handler;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import arc.util.Log;
import plugin.utils.HttpUtils;
import plugin.utils.JsonUtils;
import plugin.ServerController;
import plugin.type.PaginationRequest;
import dto.LoginDto;
import dto.LoginRequestDto;
import dto.ServerDto;
import mindustry.gen.Player;

public class ApiGateway {

    private static final String GATEWAY_URL = "http://server-manager:8088/gateway/v2/";
    private static final String SERVER_ID = ServerController.SERVER_ID.toString();

    private static final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    private static Cache<PaginationRequest, List<ServerDto>> serverQueryCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(15))
            .maximumSize(10)
            .build();

    public static void requestConnection() {
        HttpUtils.get(HttpUtils.get(GATEWAY_URL, "servers", SERVER_ID, "request-connection"));
    }

    public static int getTotalPlayer() {
        try {
            return HttpUtils.send(HttpUtils.get("total-player"), Integer.class);
        } catch (Exception e) {
            e.printStackTrace();
            return 0;
        }
    }

    public static LoginDto login(Player player) {
        try {
            var body = new LoginRequestDto()
                    .setUuid(player.uuid())
                    .setName(player.name())
                    .setIp(player.ip());

            return HttpUtils
                    .send(HttpUtils
                            .post("servers", SERVER_ID, "login")
                            .header("Content-Type", "application/json")//
                            .content(JsonUtils.toJsonString(body)), LoginDto.class);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static String host(String targetServerId) {
        Object lock = locks.computeIfAbsent(targetServerId, k -> new Object());

        synchronized (lock) {
            Log.info("Hosting server: " + targetServerId);
            try {
                return HttpUtils
                        .send(HttpUtils
                                .post("host")
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
                                HttpUtils.get(
                                        String.format("servers?page=%s&size=%s", request.getPage(), request.getSize())),
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
            return HttpUtils
                    .send(HttpUtils
                            .post(String.format("translate/%s", targetLanguage))
                            .header("Content-Type", "text/plain")//
                            .content(text), String.class);
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
