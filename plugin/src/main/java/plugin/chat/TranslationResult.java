package plugin.chat;

/**
 * Result of a translation request.
 *
 * @param translatedText The translated message content.
 * @param sourceLanguage The detected source language code (e.g., "vi", "en", "ru"). May be null if undetermined.
 */
public record TranslationResult(String translatedText, String sourceLanguage) {
}
