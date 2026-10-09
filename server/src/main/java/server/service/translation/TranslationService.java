package server.service.translation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import arc.util.Log;
import common.translation.TranslationResponse;
import server.service.MultiSourceProxyPool;
import server.service.TranslationProvider;
import server.service.translation.provider.GoogleWebProvider;
import server.service.translation.provider.LingvaProvider;

public class TranslationService {

    private static final int MAX_ATTEMPTS = 3;

    public record RegisteredProvider(int tier, int order, TranslationProvider provider, ProviderState state) {

        /**
         * Returns true if both the centralized state tracker and the provider's own
         * isAvailable() check pass (allows custom providers to add extra gating).
         */
        public boolean isAvailable() {
            return state.isAvailable() && provider.isAvailable();
        }
    }

    private final List<RegisteredProvider> registeredProviders = new CopyOnWriteArrayList<>();
    private final Map<Integer, Integer> tierRoundRobinIndices = new ConcurrentHashMap<>();
    private final Cache<String, TranslationResponse> cache;

    public TranslationService() {
        this(Caffeine.newBuilder()
                .maximumSize(5000)
                .expireAfterWrite(2, TimeUnit.HOURS)
                .build());

        registerProvider(0, 0, new GoogleWebProvider(new MultiSourceProxyPool()));
        registerProvider(1, 50, new LingvaProvider());
        registerProvider(1, 100, new GoogleWebProvider());
    }

    public TranslationService(Cache<String, TranslationResponse> cache, TranslationProvider... initialProviders) {
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
                registeredProviders.add(new RegisteredProvider(tier, order, provider, new ProviderState()));
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
     * Returns the ProviderState for the given provider instance, or null if not registered.
     */
    public ProviderState getProviderState(TranslationProvider provider) {
        return registeredProviders.stream()
                .filter(r -> r.provider() == provider)
                .map(RegisteredProvider::state)
                .findFirst()
                .orElse(null);
    }

    /**
     * Translates the given plain text into the target language.
     * Checks server-side cache first.
     *
     * <p>Provider selection follows tier ordering:
     * <ol>
     *   <li>Within the active tier, selects from available (non-cooldown) providers not yet tried
     *       in this request via round-robin.</li>
     *   <li>If no untried providers remain in the active tier, advances to the next tier.</li>
     *   <li>Retries up to {@value #MAX_ATTEMPTS} total attempts across any tiers.</li>
     *   <li>If a provider fails (exception or null/blank result), records the failure in its
     *       ProviderState (applying cooldown backoff) and tries the next candidate.</li>
     * </ol>
     *
     * @param text       Plain text to translate.
     * @param targetLang Target language code (e.g., "en", "vi").
     * @return TranslationResponse or null if no provider succeeded within MAX_ATTEMPTS.
     */
    public TranslationResponse translate(String text, String targetLang) {
        if (text == null || text.isBlank() || targetLang == null || targetLang.isBlank()) {
            return null;
        }

        String cacheKey = targetLang.toLowerCase(Locale.ROOT) + ":" + text.trim();
        TranslationResponse cached = cache.getIfPresent(cacheKey);
        if (cached != null) {
            Log.debug("Server translation cache hit for [@]: '@'", targetLang, text);
            return cached;
        }

        // Group providers by tier in ascending order
        Map<Integer, List<RegisteredProvider>> tieredProviders = registeredProviders.stream()
                .collect(Collectors.groupingBy(RegisteredProvider::tier));

        List<Integer> sortedTiers = new ArrayList<>(tieredProviders.keySet());
        Collections.sort(sortedTiers);

        // Track providers already attempted in this request (by identity to avoid issues with equals())
        Set<TranslationProvider> attemptedProviders = Collections.newSetFromMap(new IdentityHashMap<>());
        int totalAttempts = 0;

        while (totalAttempts < MAX_ATTEMPTS) {
            // Find the next candidate across tiers in priority order
            RegisteredProvider candidate = null;
            int candidateTier = -1;

            for (int tier : sortedTiers) {
                List<RegisteredProvider> tierList = tieredProviders.get(tier);
                if (tierList == null || tierList.isEmpty()) {
                    continue;
                }

                // Collect available, not-yet-attempted providers in this tier
                List<RegisteredProvider> eligible = tierList.stream()
                        .filter(r -> r.isAvailable() && !attemptedProviders.contains(r.provider()))
                        .collect(Collectors.toList());

                if (eligible.isEmpty()) {
                    continue;
                }

                // Round-robin within this tier using eligible providers
                int idx = tierRoundRobinIndices.merge(tier, 1, Integer::sum) - 1;
                candidate = eligible.get(Math.floorMod(idx, eligible.size()));
                candidateTier = tier;
                break;
            }

            if (candidate == null) {
                // No eligible providers remaining at all
                break;
            }

            TranslationProvider provider = candidate.provider();
            attemptedProviders.add(provider);
            totalAttempts++;

            long startTime = System.currentTimeMillis();
            try {
                TranslationResponse result = provider.translate(text, targetLang);
                long durationMillis = System.currentTimeMillis() - startTime;

                if (result != null && result.getTranslatedText() != null && !result.getTranslatedText().isBlank()) {
                    candidate.state().recordSuccess(provider.name(), durationMillis);
                    cache.put(cacheKey, result);
                    Log.debug("Translated via '@' [tier: @, @ -> @]: '@' -> '@' (@ms)",
                            provider.name(), candidateTier, result.getSourceLanguage(), targetLang, text, result.getTranslatedText(), durationMillis);
                    return result;
                }

                // Provider returned null/blank — treat as failure
                candidate.state().recordFailure(provider.name(), null);
                Log.warn("Translation provider '@' returned null/blank result (attempt @/@ for '@'). Trying next provider.",
                        provider.name(), totalAttempts, MAX_ATTEMPTS, text);

            } catch (Exception e) {
                candidate.state().recordFailure(provider.name(), e);
                Log.warn("Translation provider '@' threw exception (attempt @/@ for '@'): @. Trying next provider.",
                        provider.name(), totalAttempts, MAX_ATTEMPTS, text, e.getMessage());
            }
        }

        if (totalAttempts == 0) {
            Log.warn("All translation providers unavailable or in cooldown for text '@' (targetLang: '@')", text, targetLang);
        } else {
            Log.warn("All @ translation attempt(s) failed for text '@' (targetLang: '@')", totalAttempts, text, targetLang);
        }
        return null;
    }
}
