package plugin.chat;

/**
 * Interface representing a translation service provider.
 * Implementations can be ordered and managed with fallback capabilities.
 */
public interface TranslationProvider {

    /**
     * @return Unique human-readable name of the provider.
     */
    String name();

    /**
     * @return Execution priority order. Lower values indicate higher priority.
     */
    default int getOrder() {
        return 0;
    }

    /**
     * @return Whether this provider is currently available to process requests (e.g. not in circuit breaker cooldown).
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * Translates the given plain text to the target language.
     *
     * @param text       Plain text to translate (colors stripped).
     * @param targetLang Target language code (e.g., "en", "vi").
     * @return TranslationResult containing translated text and detected source language.
     * @throws Exception If translation fails or the service is unreachable.
     */
    TranslationResult translate(String text, String targetLang) throws Exception;
}
