package server.service;

import org.junit.jupiter.api.Test;
import dto.TranslationResponseDto;

import static org.junit.jupiter.api.Assertions.*;

public class LingvaProviderTest {

    @Test
    public void testParseValidResponseWithDetectedSource() throws Exception {
        LingvaProvider provider = new LingvaProvider();
        String json = "{\"translation\":\"Hello\",\"info\":{\"detectedSource\":\"vi\",\"pronunciation\":{\"query\":null,\"translation\":null}}}";

        TranslationResponseDto result = provider.parseResponse(json);

        assertNotNull(result);
        assertEquals("Hello", result.getTranslatedText());
        assertEquals("vi", result.getSourceLanguage());
    }

    @Test
    public void testParseResponseWithoutInfo() throws Exception {
        LingvaProvider provider = new LingvaProvider();
        String json = "{\"translation\":\"Bonjour\"}";

        TranslationResponseDto result = provider.parseResponse(json);

        assertNotNull(result);
        assertEquals("Bonjour", result.getTranslatedText());
        assertNull(result.getSourceLanguage());
    }

    @Test
    public void testParseInvalidResponseThrowsException() {
        LingvaProvider provider = new LingvaProvider();

        assertThrows(IllegalArgumentException.class, () -> {
            provider.parseResponse("{\"error\":\"Invalid target language\"}");
        });

        assertThrows(IllegalArgumentException.class, () -> {
            provider.parseResponse("[]");
        });
    }

    @Test
    public void testCooldownHandling() {
        LingvaProvider provider = new LingvaProvider();
        assertTrue(provider.isAvailable());
        assertEquals(0, provider.getFailureCount());

        provider.triggerCooldown();
        assertFalse(provider.isAvailable());
        assertEquals(1, provider.getFailureCount());

        provider.triggerCooldown();
        assertEquals(2, provider.getFailureCount());

        provider.resetCooldown();
        assertTrue(provider.isAvailable());
        assertEquals(0, provider.getFailureCount());
    }
}
