package server.service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import arc.util.Log;
import dto.TranslationResponseDto;
import server.utils.HttpClients;

public class LingvaProvider implements TranslationProvider {
    private static final String BASE_ENDPOINT = "https://lingva-api.onrender.com/api/v1/auto";
    private static final String USER_AGENT = "MindustryServerManager/1.0";

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration BASE_COOLDOWN = Duration.ofSeconds(5);
    private static final Duration MAX_COOLDOWN = Duration.ofMinutes(5);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private volatile Instant cooldownUntil = Instant.MIN;

    public LingvaProvider() {
        this(HttpClients.shared(), new ObjectMapper());
    }

    public LingvaProvider(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "lingva";
    }

    @Override
    public boolean isAvailable() {
        return Instant.now().isAfter(cooldownUntil);
    }

    public void triggerCooldown() {
        int failures = failureCount.incrementAndGet();
        long multiplier = 1L << Math.min(failures - 1, 6);
        long seconds = Math.min(MAX_COOLDOWN.toSeconds(), BASE_COOLDOWN.toSeconds() * multiplier);
        this.cooldownUntil = Instant.now().plusSeconds(seconds);
        Log.info("Translation provider '@' is in cooldown for @s (failures: @) until @", name(), seconds, failures, cooldownUntil);
        Log.warn("LingvaProvider placed in cooldown for @s (failures: @) until @", seconds, failures, cooldownUntil);
    }

    public void resetCooldown() {
        this.failureCount.set(0);
        this.cooldownUntil = Instant.MIN;
    }

    public int getFailureCount() {
        return failureCount.get();
    }

    public Instant getCooldownUntil() {
        return cooldownUntil;
    }

    @Override
    public TranslationResponseDto translate(String text, String targetLang) throws Exception {
        if (text == null || text.isBlank() || targetLang == null || targetLang.isBlank()) {
            return null;
        }

        if (!isAvailable()) {
            throw new IllegalStateException("LingvaProvider is currently in cooldown until " + cooldownUntil);
        }

        String encodedTarget = URLEncoder.encode(targetLang.trim(), StandardCharsets.UTF_8).replace("+", "%20");
        String encodedQuery = URLEncoder.encode(text.trim(), StandardCharsets.UTF_8).replace("+", "%20");
        String url = BASE_ENDPOINT + "/" + encodedTarget + "/" + encodedQuery;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            triggerCooldown();
            throw e;
        }

        if (response.statusCode() == 429) {
            triggerCooldown();
            throw new RuntimeException("LingvaProvider received HTTP 429 Too Many Requests");
        }

        if (response.statusCode() != 200) {
            if (response.statusCode() >= 500) {
                triggerCooldown();
            }
            throw new RuntimeException("LingvaProvider failed with HTTP " + response.statusCode() + ": " + response.body());
        }

        failureCount.set(0);
        return parseResponse(response.body());
    }

    public TranslationResponseDto parseResponse(String jsonBody) throws Exception {
        JsonNode root = objectMapper.readTree(jsonBody);
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("Invalid response format from Lingva API: " + jsonBody);
        }

        JsonNode translationNode = root.get("translation");
        if (translationNode == null || translationNode.isNull()) {
            throw new IllegalArgumentException("Missing translation field in Lingva API response: " + jsonBody);
        }

        String translatedText = translationNode.asText();
        String sourceLanguage = null;

        JsonNode infoNode = root.get("info");
        if (infoNode != null && infoNode.isObject()) {
            JsonNode detectedSourceNode = infoNode.get("detectedSource");
            if (detectedSourceNode != null && !detectedSourceNode.isNull()) {
                sourceLanguage = detectedSourceNode.asText();
            }
        }

        return new TranslationResponseDto(translatedText, sourceLanguage);
    }
}
