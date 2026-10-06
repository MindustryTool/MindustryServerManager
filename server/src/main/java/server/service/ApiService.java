package server.service;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import server.utils.ApiError;
import server.utils.HttpClients;
import server.utils.Utils;

public class ApiService {

    private final HttpClient httpClient = HttpClients.shared();

    public CompletableFuture<InputStream> getMapPreview(byte[] mapData) {
        Map<String, Object> body = Map.of("data", Base64.getEncoder().encodeToString(mapData));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.mindustry-tool.com/api/v4/maps/image-json"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Utils
                        .toJsonString(body)))
                .timeout(Duration.ofMinutes(5))
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                .thenApply(response -> {
                    if (response.statusCode() >= 400) {
                        String message = "Unknown error";
                        
                        try {
                            message = new String(response.body().readAllBytes());
                        } catch (Exception ignored) {
                        }

                        throw new ApiError(response.statusCode(),
                                "Get map preview failed: " + "https://api.mindustry-tool.com/api/v4/maps/image-json"
                                        + " - " + response.statusCode() + " - " + message);
                    }

                    return response.body();
                });
    }
}
