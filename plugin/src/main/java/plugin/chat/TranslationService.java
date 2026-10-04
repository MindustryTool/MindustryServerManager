package plugin.chat;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import arc.util.Log;
import plugin.annotations.Component;
import plugin.annotations.Init;
import plugin.core.Registry;

@Component
public class TranslationService {

    private final List<TranslationProvider> providers = new CopyOnWriteArrayList<>();
    private final Cache<String, TranslationResult> cache;

    public TranslationService() {
        this(Caffeine.newBuilder()
                .maximumSize(5000)
                .expireAfterWrite(2, TimeUnit.HOURS)
                .build());
    }

    public TranslationService(Cache<String, TranslationResult> cache) {
        this.cache = cache;
    }

    @Init
    private void init() {
        List<TranslationProvider> discovered = Registry.getAll(TranslationProvider.class);
        for (TranslationProvider provider : discovered) {
            registerProvider(provider);
        }
    }

    public synchronized void registerProvider(TranslationProvider provider) {
        if (!providers.contains(provider)) {
            providers.add(provider);
            providers.sort(Comparator.comparingInt(TranslationProvider::getOrder));
            Log.info("Registered translation provider: @ (order: @)", provider.name(), provider.getOrder());
        }
    }

    public List<TranslationProvider> getProviders() {
        return List.copyOf(providers);
    }

    public Cache<String, TranslationResult> getCache() {
        return cache;
    }

    /**
     * Translates the given plain text into the target language.
     * Checks in-memory cache first, then attempts registered providers in order.
     *
     * @param text       Plain text to translate.
     * @param targetLang Target language code (e.g. "en", "vi").
     * @return TranslationResult containing translated text and detected source language, or null if all providers fail.
     */
    public TranslationResult translate(String text, String targetLang) {
        if (text == null || text.isBlank() || targetLang == null || targetLang.isBlank()) {
            return null;
        }

        String cacheKey = targetLang.toLowerCase(Locale.ROOT) + ":" + text;
        TranslationResult cached = cache.getIfPresent(cacheKey);
        if (cached != null) {
            return cached;
        }

        for (TranslationProvider provider : providers) {
            if (!provider.isAvailable()) {
                continue;
            }

            try {
                TranslationResult result = provider.translate(text, targetLang);
                if (result != null && result.translatedText() != null && !result.translatedText().isBlank()) {
                    cache.put(cacheKey, result);
                    return result;
                }
            } catch (Exception e) {
                Log.warn("Translation provider '@' failed: @", provider.name(), e.getMessage());
            }
        }

        return null;
    }
}
