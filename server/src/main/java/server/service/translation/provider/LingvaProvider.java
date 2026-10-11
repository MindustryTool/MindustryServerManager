package server.service.translation.provider;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.io.IOException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import common.translation.TranslationResponse;
import server.service.translation.TranslationException;
import server.service.translation.TranslationProvider;
import server.utils.HttpClients;

public class LingvaProvider implements TranslationProvider {
    private static final String BASE_ENDPOINT = "https://lingva-api.onrender.com/api/v1/auto";
    private static final String USER_AGENT = "MindustryServerManager/1.0";

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

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
    public TranslationResponse translate(String text, String targetLang) throws Exception {
        if (text == null || text.isBlank() || targetLang == null || targetLang.isBlank()) {
            return null;
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
        } catch (IOException e) {
            throw TranslationException.fromIoError("lingva", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TranslationException.Temporary("lingva interrupted", e);
        }

        if (response.statusCode() != 200) {
            throw TranslationException.fromHttpStatus("lingva", response.statusCode(), response.body());
        }

        return parseResponse(response.body());
    }

    public TranslationResponse parseResponse(String jsonBody) throws Exception {
        try {
            JsonNode root = objectMapper.readTree(jsonBody);
            if (root == null || !root.isObject()) {
                throw new TranslationException.Permanent("Invalid response format from Lingva API: " + jsonBody);
            }

            JsonNode translationNode = root.get("translation");
            if (translationNode == null || translationNode.isNull()) {
                throw new TranslationException.Permanent("Missing translation field in Lingva API response: " + jsonBody);
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

            return new TranslationResponse(translatedText, sourceLanguage);
        } catch (TranslationException e) {
            throw e;
        } catch (JsonProcessingException e) {
            throw new TranslationException.Permanent("lingva malformed JSON: " + e.getMessage(), e);
        }
    }
}
