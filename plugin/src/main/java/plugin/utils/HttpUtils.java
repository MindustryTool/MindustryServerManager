package plugin.utils;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import arc.util.Http;
import arc.util.Http.HttpRequest;
import arc.util.Http.HttpStatusException;
import arc.util.Strings;

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
        return send(req, Duration.ofSeconds(10), clazz);
    }

    @SuppressWarnings("unchecked")
    public static <T> T send(HttpRequest req, Duration timeout, Class<T> clazz) {
        byte[] response = send(req, timeout);

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

    public static <T> List<T> sendList(HttpRequest req, Duration timeout, Class<T> clazz) {
        byte[] response = send(req, timeout);
        return JsonUtils.readJsonAsArrayClass(new String(response), clazz);
    }

    public static byte[] send(HttpRequest req, Duration timeout) {
        CompletableFuture<byte[]> res = new CompletableFuture<>();
        req
                .timeout((int) timeout.toMillis())
                .redirects(true)
                .error(error -> {
                    if (error instanceof SocketTimeoutException) {
                        res.completeExceptionally(
                                new RuntimeException(req.url + " timeout in " + timeout.toMillis() + "ms", error));
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
            return res.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(req.method + " " + req.url + " timeout in " + timeout.toMillis() + "ms", e);
        }
    }
}
