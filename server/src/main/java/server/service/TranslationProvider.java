package server.service;

import common.translation.TranslationResponse;

/**
 * Functional contract for a translation provider backend.
 * Priority, tiering, and scheduling are managed externally by the service registration.
 */
public interface TranslationProvider {

    /**
     * @return Unique human-readable name of the provider.
     */
    String name();

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
     * @return TranslationResponse containing translated text and detected source language.
     * @throws Exception If translation fails or the service is unreachable.
     */
    TranslationResponse translate(String text, String targetLang) throws Exception;
}
