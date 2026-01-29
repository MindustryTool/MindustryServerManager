package plugin.utils;

import java.net.SocketTimeoutException;
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
        return send(req, 10000, clazz);
    }

    @SuppressWarnings("unchecked")
    public static <T> T send(HttpRequest req, int timeoutMilis, Class<T> clazz) {
        byte[] response = send(req, timeoutMilis);

        if (clazz.equals(Void.class)) {
            return null;
        }

        if (clazz.equals(String.class)) {
            return (T) new String(response);
        }

        if (clazz.equals(byte[].class)) {
            return (T) response;
        }

        return JsonUtils.readJsonAsClass(new String(response), clazz);
    }

    public static <T> List<T> sendList(HttpRequest req, int timeoutMilis, Class<T> clazz) {
        byte[] response = send(req, timeoutMilis);
        return JsonUtils.readJsonAsArrayClass(new String(response), clazz);
    }

    public static byte[] send(HttpRequest req, int timeoutMilis) {
        CompletableFuture<byte[]> res = new CompletableFuture<>();
        req
                .header("X-SERVER-ID", ServerController.SERVER_ID.toString())
                .timeout(timeoutMilis)
                .redirects(true)
                .error(error -> {
                    if (error instanceof SocketTimeoutException) {
                        res.completeExceptionally(
                                new RuntimeException(req.url + " timeout in " + timeoutMilis + "ms", error));
                    } else if (error instanceof HttpStatusException e) {
                        res.completeExceptionally(
                                new RuntimeException(req.url + " " + e.response.getResultAsString(), e));
                    } else {
                        res.completeExceptionally(new RuntimeException(req.url, error));
                    }
                })
                .submit(response -> {
                    res.complete(response.getResult());
                });
        try {
            return res.get(timeoutMilis, TimeUnit.MILLISECONDS);
        } catch (Throwable e) {
            throw new IllegalStateException(req.method + " " + req.url + " timeout in " + timeoutMilis + "ms", e);
        }
    }
}
