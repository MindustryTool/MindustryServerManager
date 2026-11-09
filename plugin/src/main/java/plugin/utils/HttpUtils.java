package plugin.utils;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import arc.util.Strings;

public class HttpUtils {
    private static String toPath(Object... path) {
        List<String> pathList = new ArrayList<>();
        for (Object p : path) {
            pathList.add(p.toString());
        }
        return Strings.join("/", pathList);
    }

    public static HttpRequest.Builder get(Object... path) {
        return HttpRequest.newBuilder()
                .uri(URI.create(toPath(path)))
                .GET();
    }

    public static HttpRequest.Builder post(Object... path) {
        return HttpRequest.newBuilder()
                .uri(URI.create(toPath(path)));
    }

    public static <T> T send(HttpRequest.Builder builder, Class<T> clazz) {
        return send(builder, Duration.ofSeconds(10), clazz);
    }

    @SuppressWarnings("unchecked")
    public static <T> T send(HttpRequest.Builder builder, Duration timeout, Class<T> clazz) {
        byte[] response = send(builder, timeout);

        if (clazz.equals(Void.class)) {
            return null;
        }

        if (clazz.equals(String.class)) {
            return (T) new String(response, StandardCharsets.UTF_8);
        }

        if (clazz.equals(byte[].class)) {
            return (T) response;
        }

        return JsonUtils.readJsonAsClass(new String(response, StandardCharsets.UTF_8), clazz);
    }

    public static <T> List<T> sendList(HttpRequest.Builder builder, Duration timeout, Class<T> clazz) {
        byte[] response = send(builder, timeout);
        return JsonUtils.readJsonAsArrayClass(new String(response, StandardCharsets.UTF_8), clazz);
    }

    public static byte[] send(HttpRequest.Builder builder, Duration timeout) {
        return send(HttpClients.shared(), builder, timeout);
    }

    static byte[] send(HttpClient client, HttpRequest.Builder builder, Duration timeout) {
        HttpRequest request = builder.timeout(timeout).build();
        try {
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            int status = response.statusCode();
            if (status >= 400) {
                String body = new String(response.body(), StandardCharsets.UTF_8);
                throw new RuntimeException(request.uri() + " " + body);
            }
            return response.body();
        } catch (SocketTimeoutException e) {
            throw new RuntimeException(request.uri() + " timeout in " + timeout.toMillis() + "ms", e);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException(request.uri().toString(), e);
        }
    }

    static <T> List<T> sendList(HttpClient client, HttpRequest.Builder builder, Duration timeout, Class<T> clazz) {
        byte[] response = send(client, builder, timeout);
        return JsonUtils.readJsonAsArrayClass(new String(response, StandardCharsets.UTF_8), clazz);
    }
}
