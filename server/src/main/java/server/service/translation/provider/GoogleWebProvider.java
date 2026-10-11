package server.service.translation.provider;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.io.IOException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import common.translation.TranslationResponse;
import server.service.MultiSourceProxyPool;
import server.service.TrackedProxy;
import server.service.translation.TranslationException;
import server.service.translation.TranslationProvider;
import server.utils.HttpClients;

public class GoogleWebProvider implements TranslationProvider {
    private static final String ENDPOINT = "https://translate.googleapis.com/translate_a/single";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final Duration DIRECT_REQUEST_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration PROXY_CONNECT_TIMEOUT = Duration.ofSeconds(4);
    private static final Duration PROXY_REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final int MAX_PROXY_ATTEMPTS = 5;
    private static final Pattern HTML_ENTITY_PATTERN = Pattern.compile("&#(\\d+);|&#x([0-9a-fA-F]+);");

    private final String name;
    private final MultiSourceProxyPool proxyPool;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public GoogleWebProvider() {
        this("google-web", null);
    }

    public GoogleWebProvider(MultiSourceProxyPool proxyPool) {
        this("google-web-proxy", proxyPool);
    }

    public GoogleWebProvider(String name, MultiSourceProxyPool proxyPool) {
        this(name, proxyPool, proxyPool != null
                ? HttpClients.createProxied(proxyPool.asProxySelector(), PROXY_CONNECT_TIMEOUT)
                : HttpClients.shared(), new ObjectMapper());
    }

    public GoogleWebProvider(String name, MultiSourceProxyPool proxyPool, HttpClient httpClient, ObjectMapper objectMapper) {
        this.name = name != null ? name : "google-web";
        this.proxyPool = proxyPool;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return name;
    }

    public boolean isProxied() {
        return proxyPool != null;
    }

    public MultiSourceProxyPool getProxyPool() {
        return proxyPool;
    }

    @Override
    public TranslationResponse translate(String text, String targetLang) throws Exception {
        if (text == null || text.isBlank() || targetLang == null || targetLang.isBlank()) {
            return null;
        }

        String url = String.format("%s?client=gtx&sl=auto&tl=%s&dt=t&q=%s",
                ENDPOINT,
                URLEncoder.encode(targetLang.trim(), StandardCharsets.UTF_8),
                URLEncoder.encode(text.trim(), StandardCharsets.UTF_8));

        if (proxyPool != null) {
            return executeProxiedRequest(url);
        } else {
            return executeDirectRequest(url);
        }
    }

    private TranslationResponse executeDirectRequest(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .timeout(DIRECT_REQUEST_TIMEOUT)
                .GET()
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw TranslationException.fromIoError("google-web", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TranslationException.Temporary("google-web interrupted", e);
        }

        if (response.statusCode() != 200) {
            throw TranslationException.fromHttpStatus("google-web", response.statusCode(), response.body());
        }

        return parseResponse(response.body());
    }

    private TranslationResponse executeProxiedRequest(String url) throws Exception {
        Exception lastException = null;

        for (int attempt = 1; attempt <= MAX_PROXY_ATTEMPTS; attempt++) {
            TrackedProxy candidate = proxyPool.acquire();
            if (candidate == null) {
                break;
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", USER_AGENT)
                    .timeout(PROXY_REQUEST_TIMEOUT)
                    .GET()
                    .build();

            try {
                // Uses the single long-lived proxied client; the selector resolves to the
                // proxy bound by acquire() on this thread, so tracked == actually used.
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

                if (response.statusCode() == 200) {
                    proxyPool.recordSuccess(candidate);
                    return parseResponse(response.body());
                }

                proxyPool.recordFailure(candidate);
                lastException = TranslationException.fromHttpStatus("google-web-proxy",
                        response.statusCode(), response.body());
            } catch (TranslationException e) {
                proxyPool.recordFailure(candidate);
                lastException = e;
            } catch (Exception e) {
                proxyPool.recordFailure(candidate);
                lastException = e;
            } finally {
                proxyPool.release();
            }
        }

        if (lastException == null) {
            throw new TranslationException.Temporary("No proxies available in MultiSourceProxyPool");
        }

        if (lastException instanceof TranslationException te) {
            throw te;
        }
        if (lastException instanceof IOException ioe) {
            throw TranslationException.fromIoError("google-web-proxy", ioe);
        }
        throw new TranslationException.Temporary("All " + MAX_PROXY_ATTEMPTS + " proxied attempts failed: " +
                lastException.getMessage(), lastException);
    }

    public TranslationResponse parseResponse(String jsonBody) throws Exception {
        try {
            JsonNode root = objectMapper.readTree(jsonBody);
        if (root == null || !root.isArray() || root.isEmpty()) {
            throw new TranslationException.Permanent("Invalid response format from Google Web Translate: " + jsonBody);
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
        return new TranslationResponse(decodedText, sourceLanguage);
        } catch (TranslationException e) {
            throw e;
        } catch (JsonProcessingException e) {
            throw new TranslationException.Permanent("google-web malformed JSON: " + e.getMessage(), e);
        }
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
