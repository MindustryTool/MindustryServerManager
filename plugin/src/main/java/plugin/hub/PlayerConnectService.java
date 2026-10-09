package plugin.hub;

import arc.util.Log;
import lombok.RequiredArgsConstructor;
import plugin.Cfg;
import plugin.annotations.Component;
import plugin.annotations.ConditionOn;
import plugin.annotations.Destroy;
import plugin.annotations.Init;
import plugin.core.Scheduler;
import plugin.utils.JsonUtils;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@RequiredArgsConstructor
@ConditionOn(Cfg.OnHub.class)
public class PlayerConnectService {
    private static final String DEFAULT_SSE_URL = "https://api.mindustry-tool.com/api/v4/player-connect/sse";

    private final Scheduler scheduler;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private CompletableFuture<Void> streamFuture;

    private List<PlayerConnectRoom> activeRooms = Collections.emptyList();

    @Init
    public void start() {
        if (running.compareAndSet(false, true)) {
            Log.info("Starting PlayerConnectService SSE connection...");
            connectSse();
        }
    }

    @Destroy
    public void stop() {
        running.set(false);
        if (streamFuture != null) {
            streamFuture.cancel(true);
        }
        activeRooms = Collections.emptyList();
    }

    public synchronized List<PlayerConnectRoom> getActiveRooms() {
        return new ArrayList<>(activeRooms);
    }

    public synchronized int getTotalPlayerCount() {
        int count = 0;
        for (var room : activeRooms) {
            if (room.getData() != null && room.getData().getPlayers() != null) {
                count += room.getData().getPlayers().size();
            }
        }
        return count;
    }

    private synchronized void updateRooms(List<PlayerConnectRoom> rooms) {
        this.activeRooms = rooms != null ? new ArrayList<>(rooms) : Collections.emptyList();
    }

    private void connectSse() {
        if (!running.get()) {
            return;
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(getEndpointUrl()))
                .timeout(Duration.ofSeconds(60))
                .header("Accept", "text/event-stream")
                .header("Cache-Control", "no-cache")
                .GET()
                .build();

        streamFuture = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                .thenAccept(response -> {
                    if (response.statusCode() != 200) {
                        throw new IllegalStateException("SSE endpoint returned HTTP " + response.statusCode());
                    }
                    readSseStream(response.body());
                })
                .exceptionally(err -> {
                    if (running.get()) {
                        Log.err("PlayerConnectService SSE stream error: " + err.getMessage());
                        scheduleReconnect();
                    }
                    return null;
                });
    }

    private void readSseStream(InputStream inputStream) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            StringBuilder dataBuffer = new StringBuilder();

            while (running.get() && (line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    if (dataBuffer.length() > 0) {
                        handleSsePayload(dataBuffer.toString());
                        dataBuffer.setLength(0);
                    }
                } else if (line.startsWith("data:")) {
                    String data = line.substring(5).trim();
                    if (dataBuffer.length() > 0) {
                        dataBuffer.append("\n");
                    }
                    dataBuffer.append(data);
                }
            }

            if (dataBuffer.length() > 0) {
                handleSsePayload(dataBuffer.toString());
            }
        } catch (Exception e) {
            if (running.get()) {
                Log.err("Error reading PlayerConnect SSE stream: " + e.getMessage());
            }
        } finally {
            if (running.get()) {
                scheduleReconnect();
            }
        }
    }

    private void handleSsePayload(String jsonPayload) {
        if (jsonPayload == null || jsonPayload.isBlank()) {
            return;
        }

        if (!jsonPayload.trim().startsWith("{")) {
            Log.info("PlayerConnect SSE message: " + jsonPayload);
            return;
        }

        try {
            PlayerConnectRoomsEvent event = JsonUtils.readJsonAsClass(jsonPayload, PlayerConnectRoomsEvent.class);
            if (event != null && event.getRooms() != null) {
                updateRooms(event.getRooms());
            }
        } catch (Exception e) {
            Log.err("Failed to parse PlayerConnect SSE event: " + e.getMessage());
        }
    }

    private void scheduleReconnect() {
        if (!running.get()) {
            return;
        }
        scheduler.schedule(() -> {
            if (running.get()) {
                Log.info("Reconnecting to PlayerConnect SSE...");
                connectSse();
            }
        }, 5, TimeUnit.SECONDS);
    }

    private String getEndpointUrl() {
        return DEFAULT_SSE_URL;
    }
}
