package server.service;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import common.util.MultipartBody;
import server.utils.ApiError;
import server.utils.HttpClients;

public class ApiService {

    private final HttpClient httpClient = HttpClients.shared();

    public CompletableFuture<InputStream> getMapPreview(String filename, byte[] mapData) {
        MultipartBody body = MultipartBody.encodeFile("data", filename, mapData);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://content.mindustry-tool.com/api/v2/maps/image"))
                .header("Content-Type", body.contentType())
                .header("x-source", "server manager")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.body()))
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
                                "Get map preview failed: " + " - " + response.statusCode() + " - " + message);
                    }

                    return response.body();
                });
    }
}
