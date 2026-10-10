package server.service.translation.provider;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import common.translation.TranslationResponse;
import server.service.translation.TranslationProvider;
import server.utils.HttpClients;

public class LlamaTranslationProvider implements TranslationProvider {
    public static final String DEFAULT_ENDPOINT = "http://llama-translator:8080/completion";
    private static final Duration TIMEOUT = Duration.ofSeconds(6);

    private final String endpoint;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public LlamaTranslationProvider() {
        this(resolveEndpoint(), HttpClients.shared(), new ObjectMapper());
    }

    public LlamaTranslationProvider(String endpoint) {
        this(endpoint, HttpClients.shared(), new ObjectMapper());
    }

    public LlamaTranslationProvider(String endpoint, HttpClient httpClient, ObjectMapper objectMapper) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint must not be null");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    private static String resolveEndpoint() {
        String env = System.getenv("LLAMA_TRANSLATOR_URL");
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        return DEFAULT_ENDPOINT;
    }

    @Override
    public String name() {
        return "llama-local";
    }

    public String getEndpoint() {
        return endpoint;
    }

    @Override
    public TranslationResponse translate(String text, String targetLang) throws Exception {
        if (text == null || text.isBlank() || targetLang == null || targetLang.isBlank()) {
            return null;
        }

        String prompt = buildPrompt(text.trim(), targetLang.trim());
        CompletionRequest requestBody = new CompletionRequest(
                prompt,
                0.0,
                64,
                List.of("<|im_end|>", "\n", "<|endoftext|>"),
                false
        );

        String jsonPayload = objectMapper.writeValueAsString(requestBody);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .timeout(TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (response.statusCode() != 200) {
            throw new RuntimeException("LlamaTranslationProvider failed with HTTP " + response.statusCode() + ": " + response.body());
        }

        return parseResponse(response.body());
    }

    public String buildPrompt(String text, String targetLang) {
        return "<|im_start|>system\n"
                + "You are a professional translator. Translate the user text directly into language code '" + targetLang + "'. "
                + "Output ONLY the translated text without explanations, greetings, or notes.<|im_end|>\n"
                + "<|im_start|>user\n"
                + text + "<|im_end|>\n"
                + "<|im_start|>assistant\n";
    }

    public TranslationResponse parseResponse(String jsonBody) throws Exception {
        if (jsonBody == null || jsonBody.isBlank()) {
            throw new IllegalArgumentException("Empty response from llama-server");
        }

        JsonNode root = objectMapper.readTree(jsonBody);
        if (!root.isObject() || !root.has("content")) {
            throw new IllegalArgumentException("Invalid response format from llama-server: " + jsonBody);
        }

        String content = root.get("content").asText();
        if (content == null || content.isBlank()) {
            return null;
        }

        // Clean up any lingering assistant artifacts or surrounding quotes
        String cleaned = content.trim();
        if (cleaned.startsWith("\"") && cleaned.endsWith("\"") && cleaned.length() > 1) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        }

        return new TranslationResponse(cleaned, null);
    }

    public record CompletionRequest(
            @JsonProperty("prompt") String prompt,
            @JsonProperty("temperature") double temperature,
            @JsonProperty("n_predict") int nPredict,
            @JsonProperty("stop") List<String> stop,
            @JsonProperty("cache_prompt") boolean cachePrompt
    ) {}
}
