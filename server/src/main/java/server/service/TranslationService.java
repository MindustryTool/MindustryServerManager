package server.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import arc.util.Log;
import dto.TranslationResponseDto;

public class TranslationService {

    public record RegisteredProvider(int tier, int order, TranslationProvider provider) {}

    private final List<RegisteredProvider> registeredProviders = new CopyOnWriteArrayList<>();
    private final Map<Integer, AtomicInteger> tierRoundRobinIndices = new ConcurrentHashMap<>();
    private final Cache<String, TranslationResponseDto> cache;

    public TranslationService() {
        this(Caffeine.newBuilder()
                .maximumSize(5000)
                .expireAfterWrite(2, TimeUnit.HOURS)
                .build());
                
        registerProvider(0, 50, new LingvaProvider());
        registerProvider(0, 100, new GoogleWebProvider());
        registerProvider(1, 100, new GoogleWebProvider(new MultiSourceProxyPool()));
    }

    public TranslationService(Cache<String, TranslationResponseDto> cache, TranslationProvider... initialProviders) {
        this.cache = cache;
        if (initialProviders != null) {
            for (int i = 0; i < initialProviders.length; i++) {
                registerProvider(0, i * 10, initialProviders[i]);
            }
        }
    }

    public synchronized void registerProvider(TranslationProvider provider) {
        registerProvider(0, 0, provider);
    }

    public synchronized void registerProvider(int tier, TranslationProvider provider) {
        registerProvider(tier, 0, provider);
    }

    public synchronized void registerProvider(int tier, int order, TranslationProvider provider) {
        if (provider != null) {
            // Avoid duplicate registrations of identical provider instance in the same tier
            boolean exists = registeredProviders.stream()
                    .anyMatch(r -> r.tier() == tier && r.provider().equals(provider));
            if (!exists) {
                registeredProviders.add(new RegisteredProvider(tier, order, provider));
                registeredProviders.sort(Comparator.comparingInt(RegisteredProvider::tier)
                        .thenComparingInt(RegisteredProvider::order));
                Log.info("Registered server translation provider: @ (tier: @, order: @)",
                        provider.name(), tier, order);
            }
        }
    }

    public List<TranslationProvider> getProviders() {
        return registeredProviders.stream()
                .map(RegisteredProvider::provider)
                .collect(Collectors.toUnmodifiableList());
    }

    public List<RegisteredProvider> getRegisteredProviders() {
        return List.copyOf(registeredProviders);
    }

    /**
     * Translates the given plain text into the target language.
     * Checks server-side cache first.
     * Evaluates providers tier by tier:
     * - Within the active tier, selects from available (non-cooldown) providers via round-robin.
     * - If no providers in a tier are available, advances to the next tier.
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

        // Group providers by tier in ascending order
        Map<Integer, List<RegisteredProvider>> tieredProviders = registeredProviders.stream()
                .collect(Collectors.groupingBy(RegisteredProvider::tier));

        List<Integer> sortedTiers = new ArrayList<>(tieredProviders.keySet());
        Collections.sort(sortedTiers);

        for (int tier : sortedTiers) {
            List<RegisteredProvider> tierList = tieredProviders.get(tier);
            if (tierList == null || tierList.isEmpty()) {
                continue;
            }

            List<RegisteredProvider> availableInTier = tierList.stream()
                    .filter(r -> r.provider().isAvailable())
                    .collect(Collectors.toList());

            if (availableInTier.isEmpty()) {
                Log.debug("All providers in tier @ are currently unavailable/cooling down, checking next tier", tier);
                continue;
            }

            AtomicInteger counter = tierRoundRobinIndices.computeIfAbsent(tier, k -> new AtomicInteger(0));
            int selectedIndex = Math.floorMod(counter.getAndIncrement(), availableInTier.size());
            TranslationProvider provider = availableInTier.get(selectedIndex).provider();

            try {
                TranslationResponseDto result = provider.translate(text, targetLang);
                if (result != null && result.getTranslatedText() != null && !result.getTranslatedText().isBlank()) {
                    cache.put(cacheKey, result);
                    Log.debug("Translated via '@' [tier: @, @ -> @]: '@' -> '@'",
                            provider.name(), tier, result.getSourceLanguage(), targetLang, text, result.getTranslatedText());
                    return result;
                }
                return null;
            } catch (Exception e) {
                Log.warn("Translation provider '@' failed: @. Failing immediately without trying remaining providers.",
                        provider.name(), e.getMessage());
                return null;
            }
        }

        Log.warn("All translation providers unavailable or in cooldown for text '@' (targetLang: '@')", text, targetLang);
        return null;
    }
}
