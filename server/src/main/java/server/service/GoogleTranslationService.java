package server.service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import arc.util.Log;
import dto.TranslationResponseDto;

public class GoogleTranslationService {
    private static final String ENDPOINT = "https://translate.googleapis.com/translate_a/single";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration BASE_COOLDOWN = Duration.ofSeconds(5);
    private static final Duration MAX_COOLDOWN = Duration.ofMinutes(5);
    private static final Pattern HTML_ENTITY_PATTERN = Pattern.compile("&#(\\d+);|&#x([0-9a-fA-F]+);");

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Cache<String, TranslationResponseDto> cache;
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private volatile Instant cooldownUntil = Instant.MIN;

    public GoogleTranslationService() {
        this(HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build(), new ObjectMapper(), Caffeine.newBuilder()
                .maximumSize(5000)
                .expireAfterWrite(2, TimeUnit.HOURS)
                .build());
    }

    public GoogleTranslationService(HttpClient httpClient, ObjectMapper objectMapper, Cache<String, TranslationResponseDto> cache) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.cache = cache;
    }

    public boolean isAvailable() {
        return Instant.now().isAfter(cooldownUntil);
    }

    public void triggerCooldown() {
        int failures = failureCount.incrementAndGet();
        long multiplier = 1L << Math.min(failures - 1, 6);
        long seconds = Math.min(MAX_COOLDOWN.toSeconds(), BASE_COOLDOWN.toSeconds() * multiplier);
        this.cooldownUntil = Instant.now().plusSeconds(seconds);
        Log.warn("GoogleTranslationService placed in cooldown for @s (failures: @) until @", seconds, failures, cooldownUntil);
    }

    public void resetCooldown() {
        this.failureCount.set(0);
        this.cooldownUntil = Instant.MIN;
    }

    public int getFailureCount() {
        return failureCount.get();
    }

    public TranslationResponseDto translate(String text, String targetLang) throws Exception {
        if (text == null || text.isBlank() || targetLang == null || targetLang.isBlank()) {
            return null;
        }

        String cacheKey = targetLang.toLowerCase(Locale.ROOT) + ":" + text.trim();
        TranslationResponseDto cached = cache.getIfPresent(cacheKey);
        if (cached != null) {
            Log.debug("Server translation cache hit for [@]: '@'", targetLang, text);
            return cached;
        }

        if (!isAvailable()) {
            throw new IllegalStateException("GoogleTranslationService is currently in cooldown until " + cooldownUntil);
        }

        String url = String.format("%s?client=gtx&sl=auto&tl=%s&dt=t&q=%s",
                ENDPOINT,
                URLEncoder.encode(targetLang, StandardCharsets.UTF_8),
                URLEncoder.encode(text, StandardCharsets.UTF_8));

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
            throw new RuntimeException("GoogleTranslationService received HTTP 429 Too Many Requests");
        }

        if (response.statusCode() != 200) {
            if (response.statusCode() >= 500) {
                triggerCooldown();
            }
            throw new RuntimeException("GoogleTranslationService failed with HTTP " + response.statusCode() + ": " + response.body());
        }

        failureCount.set(0);

        TranslationResponseDto result = parseResponse(response.body());
        if (result != null && result.getTranslatedText() != null && !result.getTranslatedText().isBlank()) {
            cache.put(cacheKey, result);
        }

        return result;
    }

    public TranslationResponseDto parseResponse(String jsonBody) throws Exception {
        JsonNode root = objectMapper.readTree(jsonBody);
        if (root == null || !root.isArray() || root.isEmpty()) {
            throw new IllegalArgumentException("Invalid response format from Google Web Translate: " + jsonBody);
        }

        StringBuilder translatedText = new StringBuilder();
        JsonNode sentences = root.get(0);
        if (sentences != null && sentences.isArray()) {
            for (JsonNode sentence : sentences) {
                if (sentence.isArray() && !sentence.isEmpty() && !sentence.get(0).isNull()) {
                    translatedText.append(sentence.get(0).asText());
                }
            }
        }

        String sourceLanguage = null;
        if (root.size() > 2 && !root.get(2).isNull()) {
            sourceLanguage = root.get(2).asText();
        }

        String decodedText = unescapeHtml(translatedText.toString());
        return new TranslationResponseDto(decodedText, sourceLanguage);
    }

    public static String unescapeHtml(String text) {
        if (text == null || !text.contains("&")) {
            return text;
        }
        String result = text
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&#39;", "'")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&nbsp;", " ");

        Matcher matcher = HTML_ENTITY_PATTERN.matcher(result);
        if (!matcher.find()) {
            return result;
        }

        StringBuilder sb = new StringBuilder();
        do {
            if (matcher.group(1) != null) {
                int code = Integer.parseInt(matcher.group(1));
                matcher.appendReplacement(sb, Matcher.quoteReplacement(Character.toString((char) code)));
            } else if (matcher.group(2) != null) {
                int code = Integer.parseInt(matcher.group(2), 16);
                matcher.appendReplacement(sb, Matcher.quoteReplacement(Character.toString((char) code)));
            }
        } while (matcher.find());
        matcher.appendTail(sb);
        return sb.toString();
    }
}
