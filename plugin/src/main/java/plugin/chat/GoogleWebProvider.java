package plugin.chat;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import arc.util.Log;
import plugin.annotations.Component;

@Component
public class GoogleWebProvider implements TranslationProvider {
    private static final String ENDPOINT = "https://translate.googleapis.com/translate_a/single";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(4);
    private static final Duration COOLDOWN_DURATION = Duration.ofMinutes(2);
    private static final Pattern HTML_ENTITY_PATTERN = Pattern.compile("&#(\\d+);|&#x([0-9a-fA-F]+);");

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private volatile Instant cooldownUntil = Instant.MIN;

    public GoogleWebProvider() {
        this(HttpClient.newBuilder()
                .connectTimeout(REQUEST_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build(), new ObjectMapper());
    }

    public GoogleWebProvider(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "google-web";
    }

    @Override
    public int getOrder() {
        return 100; // Primary provider
    }

    @Override
    public boolean isAvailable() {
        return Instant.now().isAfter(cooldownUntil);
    }

    public void triggerCooldown() {
        this.cooldownUntil = Instant.now().plus(COOLDOWN_DURATION);
        Log.warn("GoogleWebProvider placed in cooldown for @ until @", COOLDOWN_DURATION, cooldownUntil);
    }

    public void resetCooldown() {
        this.cooldownUntil = Instant.MIN;
    }

    @Override
    public TranslationResult translate(String text, String targetLang) throws Exception {
        if (!isAvailable()) {
            throw new IllegalStateException("GoogleWebProvider is currently in cooldown until " + cooldownUntil);
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
            throw new RuntimeException("GoogleWebProvider received HTTP 429 Too Many Requests");
        }

        if (response.statusCode() != 200) {
            if (response.statusCode() >= 500) {
                triggerCooldown();
            }
            throw new RuntimeException("GoogleWebProvider failed with HTTP " + response.statusCode() + ": " + response.body());
        }

        return parseResponse(response.body());
    }

    public TranslationResult parseResponse(String jsonBody) throws Exception {
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
        return new TranslationResult(decodedText, sourceLanguage);
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
