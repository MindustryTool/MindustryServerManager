package plugin.graph.services;

import graph.compile.RateLimiter;
import graph.runtime.RuntimeServices;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class GraphHttpService {

    private final HttpClient client;
    private final ExecutorService executor;
    private final RateLimiter rateLimiter;
    private final long maxResponseBytes;
    private final long maxRequestBytes;
    private final int timeoutMillis;

    public GraphHttpService(double requestsPerSecond, int burstCapacity,
                            long maxResponseBytes, long maxRequestBytes,
                            int timeoutMillis) {
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "graph-http");
            thread.setDaemon(true);
            return thread;
        });
        this.client = HttpClient.newBuilder()
                .executor(executor)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.rateLimiter = RateLimiter.create(requestsPerSecond, burstCapacity);
        this.maxResponseBytes = maxResponseBytes;
        this.maxRequestBytes = maxRequestBytes;
        this.timeoutMillis = timeoutMillis;
    }

    public CompletableFuture<RuntimeServices.HttpResult> execute(
            String key, String method, String url, Map<String, String> headers,
            Map<String, String> query, String body) {
        if (!rateLimiter.tryAcquire(key)) {
            var rejected = new CompletableFuture<RuntimeServices.HttpResult>();
            rejected.complete(RuntimeServices.HttpResult.failure(0,
                    "Rate limit exceeded for '" + key + "'"));
            return rejected;
        }
        byte[] payload = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        if (payload.length > maxRequestBytes) {
            return CompletableFuture.failedFuture(
                    new HttpLimitException("Request body exceeds "
                            + maxRequestBytes + " bytes"));
        }
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(buildUri(url, query))
                    .timeout(java.time.Duration.ofMillis(timeoutMillis));
            if (headers != null) {
                headers.forEach(builder::header);
            }
            switch (method) {
                case "POST" -> builder.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(payload));
                case "PUT" -> builder.header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(payload));
                case "DELETE" -> builder.method("DELETE",
                        HttpRequest.BodyPublishers.ofByteArray(payload));
                default -> builder.GET();
            }
            HttpRequest request = builder.build();
            return client.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                    .thenApply(this::toResult)
                    .thenApply(this::checkSize)
                    .exceptionally(this::toFailure);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private RuntimeServices.HttpResult checkSize(RuntimeServices.HttpResult result) {
        if (result.body() != null
                && result.body().getBytes(StandardCharsets.UTF_8).length > maxResponseBytes) {
            throw new HttpLimitException("Response exceeds " + maxResponseBytes + " bytes");
        }
        return result;
    }

    private RuntimeServices.HttpResult toResult(HttpResponse<byte[]> response) {
        byte[] bytes = response.body() == null ? new byte[0] : response.body();
        boolean ok = response.statusCode() >= 200 && response.statusCode() < 300;
        Map<String, String> headerMap = new java.util.HashMap<>();
        response.headers().map().forEach((name, values) ->
                headerMap.put(name.toLowerCase(),
                        values.isEmpty() ? "" : values.get(0)));
        String bodyText;
        try {
            bodyText = new String(bytes, StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            bodyText = "";
        }
        return new RuntimeServices.HttpResult(response.statusCode(), headerMap,
                bodyText, ok);
    }

    private RuntimeServices.HttpResult toFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        int status = current instanceof HttpLimitException ? 0 : 599;
        return new RuntimeServices.HttpResult(status, Map.of(),
                current.getMessage() == null
                        ? current.getClass().getSimpleName()
                        : current.getMessage(),
                false);
    }

    static URI buildUri(String url, Map<String, String> query) throws Exception {
        if (query == null || query.isEmpty()) {
            return URI.create(url);
        }
        StringBuilder sb = new StringBuilder(url);
        sb.append(url.contains("?") ? '&' : '?');
        boolean first = true;
        for (Map.Entry<String, String> entry : query.entrySet()) {
            if (!first) {
                sb.append('&');
            }
            first = false;
            sb.append(java.net.URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(java.net.URLEncoder.encode(entry.getValue(),
                            StandardCharsets.UTF_8));
        }
        return URI.create(sb.toString());
    }

    public static final class HttpLimitException extends RuntimeException {
        public HttpLimitException(String message) {
            super(message);
        }
    }
}
