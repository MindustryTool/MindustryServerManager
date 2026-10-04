package plugin.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ChatTranslationTest {

    @Test
    public void testFormatMessageWithoutTranslationAppendsPlayerName() {
        String result = ChatTranslation.formatMessage("Alice", "hello", "hello", "en", null);
        assertEquals("Alice: hello", result);
    }

    @Test
    public void testFormatMessageWithoutSenderNameFallsBack() {
        String result = ChatTranslation.formatMessage(null, "hello", "hello", "en", null);
        assertEquals("hello", result);

        String resultBlank = ChatTranslation.formatMessage("", "hello", "hello", "en", null);
        assertEquals("hello", resultBlank);
    }

    @Test
    public void testFormatMessageWithTranslationAppendsPlayerNameAndBracket() {
        TranslationResult translation = new TranslationResult("Xin chào", "en");
        String result = ChatTranslation.formatMessage("Bob", "hello", "hello", "vi", translation);
        assertEquals("Bob: hello ([#00ff00]Xin chào])", result);
    }

    @Test
    public void testFormatMessageSameSourceAndRecipientLanguageDoesNotTranslate() {
        TranslationResult translation = new TranslationResult("hello", "en");
        String result = ChatTranslation.formatMessage("Charlie", "hello", "hello", "en", translation);
        assertEquals("Charlie: hello", result);
    }

    @Test
    public void testFormatMessageIdenticalTranslationTextDoesNotTranslate() {
        TranslationResult translation = new TranslationResult("hello", "fr");
        String result = ChatTranslation.formatMessage("Dave", "hello", "hello", "es", translation);
        assertEquals("Dave: hello", result);
    }
}
