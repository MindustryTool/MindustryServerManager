package server.service;

import dto.TranslationResponseDto;

/**
 * Interface representing a translation service provider on the server manager.
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
     * Translates plain text into the target language.
     *
     * @param text       Plain text to translate.
     * @param targetLang Target language code (e.g., "en", "vi").
     * @return TranslationResponseDto containing translated text and detected source language.
     * @throws Exception If translation fails or the service is unreachable.
     */
    TranslationResponseDto translate(String text, String targetLang) throws Exception;
}
