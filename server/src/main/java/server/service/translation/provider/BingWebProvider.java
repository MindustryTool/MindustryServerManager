package server.service.translation.provider;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import common.translation.TranslationResponse;
import server.service.translation.TranslationProvider;
import server.utils.HttpClients;

public class BingWebProvider implements TranslationProvider {
    private static final String TRANSLATOR_PAGE_URL = "https://www.bing.com/translator";
    private static final String TRANSLATE_API_URL = "https://www.bing.com/ttranslatev3";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";

    private static final Duration PAGE_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration TRANSLATE_TIMEOUT = Duration.ofSeconds(8);

    private static final Pattern IG_PATTERN = Pattern.compile("IG\\s*[:=]\\s*\"([^\"]+)\"");
    private static final Pattern IID_PATTERN = Pattern.compile("data-iid=\"([^\"]+)\"");
    private static final Pattern ABUSE_PATTERN = Pattern.compile("params_AbusePreventionHelper\\s*=\\s*\\[([0-9]+),\\s*\"([^\"]+)\"\\s*,\\s*([0-9]+)\\]");
    private static final Pattern HTML_ENTITY_PATTERN = Pattern.compile("&#(\\d+);|&#x([0-9a-fA-F]+);");

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    private final Object tokenLock = new Object();
    private volatile SessionCredentials credentials;

    public record SessionCredentials(String ig, String iid, String key, String token, Instant expiry) {
        public boolean isExpired() {
            return Instant.now().isAfter(expiry);
        }
    }

    public BingWebProvider() {
        this(HttpClients.shared(), new ObjectMapper());
    }

    public BingWebProvider(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    public String name() {
        return "bing-web";
    }

    @Override
    public TranslationResponse translate(String text, String targetLang) throws Exception {
        if (text == null || text.isBlank() || targetLang == null || targetLang.isBlank()) {
            return null;
        }

        SessionCredentials session = getOrRefreshCredentials(false);
        try {
            return executeTranslate(session, text.trim(), targetLang.trim());
        } catch (SessionExpiredException e) {
            // Invalidate credentials and retry once with fresh tokens
            session = getOrRefreshCredentials(true);
            return executeTranslate(session, text.trim(), targetLang.trim());
        }
    }

    public SessionCredentials getOrRefreshCredentials(boolean forceRefresh) throws Exception {
        SessionCredentials current = this.credentials;
        if (!forceRefresh && current != null && !current.isExpired()) {
            return current;
        }

        synchronized (tokenLock) {
            current = this.credentials;
            if (!forceRefresh && current != null && !current.isExpired()) {
                return current;
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(TRANSLATOR_PAGE_URL))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .timeout(PAGE_TIMEOUT)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new RuntimeException("Failed to fetch Bing translator page: HTTP " + response.statusCode());
            }

            SessionCredentials fresh = extractCredentials(response.body());
            this.credentials = fresh;
            return fresh;
        }
    }

    public SessionCredentials extractCredentials(String html) {
        if (html == null || html.isBlank()) {
            throw new IllegalArgumentException("Bing translator page HTML is empty");
        }

        Matcher igMatcher = IG_PATTERN.matcher(html);
        String ig = igMatcher.find() ? igMatcher.group(1) : "";

        Matcher iidMatcher = IID_PATTERN.matcher(html);
        String iid = iidMatcher.find() ? iidMatcher.group(1) : "";

        Matcher abuseMatcher = ABUSE_PATTERN.matcher(html);
        if (!abuseMatcher.find()) {
            throw new IllegalStateException("Failed to extract abuse prevention parameters from Bing HTML");
        }

        String key = abuseMatcher.group(1);
        String token = abuseMatcher.group(2);
        long validityMs = Long.parseLong(abuseMatcher.group(3));

        // Use a safety margin of 60 seconds before actual expiration
        Instant expiry = Instant.now().plusMillis(Math.max(10_000, validityMs - 60_000));
        return new SessionCredentials(ig, iid, key, token, expiry);
    }

    private TranslationResponse executeTranslate(SessionCredentials session, String text, String targetLang) throws Exception {
        String apiUrl = TRANSLATE_API_URL + "?isVertical=1"
                + (session.ig().isBlank() ? "" : "&IG=" + session.ig())
                + (session.iid().isBlank() ? "" : "&IID=" + session.iid());

        String form = "fromLang=auto-detect&text=" + URLEncoder.encode(text, StandardCharsets.UTF_8)
                + "&to=" + URLEncoder.encode(targetLang, StandardCharsets.UTF_8)
                + "&token=" + URLEncoder.encode(session.token(), StandardCharsets.UTF_8)
                + "&key=" + URLEncoder.encode(session.key(), StandardCharsets.UTF_8)
                + "&tryFetchingGenderDebiasedTranslations=true";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Referer", TRANSLATOR_PAGE_URL)
                .header("Origin", "https://www.bing.com")
                .timeout(TRANSLATE_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (response.statusCode() == 429) {
            throw new RuntimeException("BingWebProvider received HTTP 429 Too Many Requests");
        }

        if (response.statusCode() != 200) {
            throw new RuntimeException("BingWebProvider failed with HTTP " + response.statusCode() + ": " + response.body());
        }

        return parseResponse(response.body());
    }

    public TranslationResponse parseResponse(String jsonBody) throws Exception {
        if (jsonBody == null || jsonBody.isBlank()) {
            throw new IllegalArgumentException("Empty response received from Bing Translate");
        }

        JsonNode root = objectMapper.readTree(jsonBody);

        // Check if error response or session expired (e.g., {"statusCode": 205})
        if (root.isObject()) {
            JsonNode statusCodeNode = root.get("statusCode");
            if (statusCodeNode != null && statusCodeNode.asInt() != 200) {
                int code = statusCodeNode.asInt();
                String msg = root.has("errorMessage") ? root.get("errorMessage").asText() : "";
                if (code == 205) {
                    throw new SessionExpiredException("Bing session token expired or invalid (code 205): " + msg);
                }
                throw new RuntimeException("Bing translation failed with status " + code + ": " + msg);
            }
        }

        if (!root.isArray() || root.isEmpty()) {
            throw new IllegalArgumentException("Invalid response format from Bing Translate: " + jsonBody);
        }

        JsonNode firstItem = root.get(0);
        JsonNode translationsNode = firstItem.get("translations");
        if (translationsNode == null || !translationsNode.isArray() || translationsNode.isEmpty()) {
            throw new IllegalArgumentException("Missing translations field in Bing response: " + jsonBody);
        }

        JsonNode primaryTranslation = translationsNode.get(0);
        JsonNode textNode = primaryTranslation.get("text");
        if (textNode == null || textNode.isNull()) {
            throw new IllegalArgumentException("Missing text in translation object: " + jsonBody);
        }

        String translatedText = unescapeHtml(textNode.asText());

        String sourceLanguage = null;
        JsonNode detectedLangNode = firstItem.get("detectedLanguage");
        if (detectedLangNode != null && detectedLangNode.has("language")) {
            sourceLanguage = detectedLangNode.get("language").asText();
        }

        return new TranslationResponse(translatedText, sourceLanguage);
    }

    public static String unescapeHtml(String input) {
        if (input == null || input.indexOf('&') == -1) {
            return input;
        }

        String text = input
                .replace("&quot;", "\"")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&nbsp;", " ");

        Matcher matcher = HTML_ENTITY_PATTERN.matcher(text);
        if (!matcher.find()) {
            return text;
        }

        StringBuilder sb = new StringBuilder();
        int lastIndex = 0;
        matcher.reset();
        while (matcher.find()) {
            sb.append(text, lastIndex, matcher.start());
            try {
                int codePoint = matcher.group(1) != null
                        ? Integer.parseInt(matcher.group(1))
                        : Integer.parseInt(matcher.group(2), 16);
                sb.append(Character.toChars(codePoint));
            } catch (Exception e) {
                sb.append(matcher.group(0));
            }
            lastIndex = matcher.end();
        }
        sb.append(text.substring(lastIndex));
        return sb.toString();
    }

    public static class SessionExpiredException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public SessionExpiredException(String message) {
            super(message);
        }
    }
}
