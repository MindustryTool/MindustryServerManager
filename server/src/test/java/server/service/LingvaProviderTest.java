package server.service;

import org.junit.jupiter.api.Test;
import common.translation.TranslationResponse;
import server.service.translation.provider.LingvaProvider;

import static org.junit.jupiter.api.Assertions.*;

public class LingvaProviderTest {

    @Test
    public void testParseValidResponseWithDetectedSource() throws Exception {
        LingvaProvider provider = new LingvaProvider();
        String json = "{\"translation\":\"Hello\",\"info\":{\"detectedSource\":\"vi\",\"pronunciation\":{\"query\":null,\"translation\":null}}}";

        TranslationResponse result = provider.parseResponse(json);

        assertNotNull(result);
        assertEquals("Hello", result.getTranslatedText());
        assertEquals("vi", result.getSourceLanguage());
    }

    @Test
    public void testParseResponseWithoutInfo() throws Exception {
        LingvaProvider provider = new LingvaProvider();
        String json = "{\"translation\":\"Bonjour\"}";

        TranslationResponse result = provider.parseResponse(json);

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
    public void testProviderIsAlwaysAvailableByDefault() {
        LingvaProvider provider = new LingvaProvider();
        // Cooldown is now managed by TranslationService.ProviderState - provider itself is always available
        assertTrue(provider.isAvailable(), "LingvaProvider should always report available (cooldown managed centrally)");
    }
}
