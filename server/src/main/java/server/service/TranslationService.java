package server.service;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import arc.util.Log;
import dto.TranslationResponseDto;

public class TranslationService {

    private final List<TranslationProvider> providers = new CopyOnWriteArrayList<>();
    private final Cache<String, TranslationResponseDto> cache;

    public TranslationService() {
        this(Caffeine.newBuilder()
                .maximumSize(5000)
                .expireAfterWrite(2, TimeUnit.HOURS)
                .build(), new GoogleWebProvider());
    }

    public TranslationService(Cache<String, TranslationResponseDto> cache, TranslationProvider... initialProviders) {
        this.cache = cache;
        if (initialProviders != null) {
            for (TranslationProvider provider : initialProviders) {
                registerProvider(provider);
            }
        }
    }

    public synchronized void registerProvider(TranslationProvider provider) {
        if (provider != null && !providers.contains(provider)) {
            providers.add(provider);
            providers.sort(Comparator.comparingInt(TranslationProvider::getOrder));
            Log.info("Registered server translation provider: @ (order: @)", provider.name(), provider.getOrder());
        }
    }

    public List<TranslationProvider> getProviders() {
        return List.copyOf(providers);
    }

    /**
     * Translates the given plain text into the target language.
     * Checks server-side cache first.
     * Evaluates providers in priority order:
     * - Skips unavailable/cooling-down providers to find the first available provider.
     * - If the active provider fails during execution, fails immediately without trying remaining providers.
     *
     * @param text       Plain text to translate.
     * @param targetLang Target language code (e.g., "en", "vi").
     * @return TranslationResponseDto or null if translation failed or no providers were available.
     */
    public TranslationResponseDto translate(String text, String targetLang) {
        if (text == null || text.isBlank() || targetLang == null || targetLang.isBlank()) {
            return null;
        }

        String cacheKey = targetLang.toLowerCase(Locale.ROOT) + ":" + text.trim();
        TranslationResponseDto cached = cache.getIfPresent(cacheKey);
        if (cached != null) {
            Log.debug("Server translation cache hit for [@]: '@'", targetLang, text);
            return cached;
        }

        for (TranslationProvider provider : providers) {
            if (!provider.isAvailable()) {
                Log.debug("Translation provider '@' is currently unavailable/cooling down, checking next", provider.name());
                continue;
            }

            try {
                TranslationResponseDto result = provider.translate(text, targetLang);
                if (result != null && result.getTranslatedText() != null && !result.getTranslatedText().isBlank()) {
                    cache.put(cacheKey, result);
                    Log.debug("Translated via '@' [@ -> @]: '@' -> '@'", provider.name(), result.getSourceLanguage(), targetLang, text, result.getTranslatedText());
                    return result;
                }
                return null;
            } catch (Exception e) {
                Log.warn("Translation provider '@' failed: @. Failing immediately without trying remaining providers.", provider.name(), e.getMessage());
                return null;
            }
        }

        Log.warn("All translation providers unavailable or in cooldown for text '@' (targetLang: '@')", text, targetLang);
        return null;
    }
}
