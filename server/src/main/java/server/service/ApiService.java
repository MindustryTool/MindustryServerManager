package server.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import arc.util.Log;
import lombok.RequiredArgsConstructor;
import server.EnvConfig;
import server.utils.ApiError;

@RequiredArgsConstructor
public class ApiService {

    private final EnvConfig envConfig;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final String BASE_URL = "https://api.mindustry-tool.com/api/v4/";

    public void requestBackendConnection() {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/managers/request-connection"))
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + envConfig.serverConfig().accessToken())
                .build();

        try {
            httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (IOException | InterruptedException e) {
            Log.err("Fail to request connection from backend", e);
        }
    }

    public CompletableFuture<byte[]> getMapPreview(byte[] mapData) {
        String boundary = "---" + System.currentTimeMillis();
        byte[] multipartBody = createMultipartBody(mapData, boundary);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "maps/image"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody))
                .timeout(Duration.ofMinutes(5))
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(response -> {
                    if (response.statusCode() >= 400) {
                        throw new ApiError(response.statusCode(),
                                "Get map preview failed: " + new String(response.body()));
                    }
                    return response.body();
                });
    }

    private byte[] createMultipartBody(byte[] data, String boundary) {
        String header = "--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"file\"; filename=\"map\"\r\n" +
                "Content-Type: application/octet-stream\r\n\r\n";
        String footer = "\r\n--" + boundary + "--\r\n";

        byte[] headerBytes = header.getBytes();
        byte[] footerBytes = footer.getBytes();

        byte[] body = new byte[headerBytes.length + data.length + footerBytes.length];
        System.arraycopy(headerBytes, 0, body, 0, headerBytes.length);
        System.arraycopy(data, 0, body, headerBytes.length, data.length);
        System.arraycopy(footerBytes, 0, body, headerBytes.length + data.length, footerBytes.length);

        return body;
    }
}
