package plugin.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import arc.util.Http;
import arc.util.Http.HttpRequest;
import arc.util.Http.HttpStatusException;
import arc.util.Strings;
import plugin.ServerController;

public class HttpUtils {
    private static String toPath(Object... path) {
        List<String> pathList = new ArrayList<>();
        for (Object p : path) {
            pathList.add(p.toString());
        }
        return Strings.join("/", pathList);
    }

    public static HttpRequest get(Object... path) {
        return Http.get(toPath(path));
    }

    public static HttpRequest post(Object... path) {
        return Http.post(toPath(path));
    }

    public static <T> T send(HttpRequest req, Class<T> clazz) {
        return send(req, 2000, clazz);
    }

    @SuppressWarnings("unchecked")
    public static <T> T send(HttpRequest req, int timeoutMilis, Class<T> clazz) {
        String response = send(req, timeoutMilis);

        if (clazz.equals(Void.class)) {
            return null;
        }

        if (clazz.equals(String.class)) {
            return (T) response;
        }

        return JsonUtils.readJsonAsClass(response, clazz);
    }

    public static <T> List<T> sendList(HttpRequest req, int timeoutMilis, Class<T> clazz) {
        String response = send(req, timeoutMilis);
        return JsonUtils.readJsonAsArrayClass(response, clazz);
    }

    public static String send(HttpRequest req, int timeoutMilis) {
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
}
