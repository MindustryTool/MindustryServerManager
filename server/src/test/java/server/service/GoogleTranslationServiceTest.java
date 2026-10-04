package server.service;

import org.junit.jupiter.api.Test;
import dto.TranslationResponseDto;

import static org.junit.jupiter.api.Assertions.*;

public class GoogleTranslationServiceTest {

    @Test
    public void testParseSingleSentence() throws Exception {
        GoogleTranslationService service = new GoogleTranslationService();
        String json = "[[[\"Xin chào\", \"Hello\", null, null, 10]], null, \"en\"]";

        TranslationResponseDto result = service.parseResponse(json);

        assertNotNull(result);
        assertEquals("Xin chào", result.getTranslatedText());
        assertEquals("en", result.getSourceLanguage());
    }

    @Test
    public void testParseMultiSegmentResponse() throws Exception {
        GoogleTranslationService service = new GoogleTranslationService();
        String json = "[[[\"Hello.\", \"Xin chào.\", null, null, 10], [\" How are you?\", \" Bạn khỏe không?\", null, null, 10]], null, \"vi\"]";

        TranslationResponseDto result = service.parseResponse(json);

        assertNotNull(result);
        assertEquals("Hello. How are you?", result.getTranslatedText());
        assertEquals("vi", result.getSourceLanguage());
    }

    @Test
    public void testHtmlEntityUnescaping() {
        assertEquals("It's fine", GoogleTranslationService.unescapeHtml("It&#39;s fine"));
        assertEquals("He said \"yes\" & 'no'", GoogleTranslationService.unescapeHtml("He said &quot;yes&quot; &amp; &#39;no&#39;"));
        assertEquals("<tag> & test", GoogleTranslationService.unescapeHtml("&lt;tag&gt; &amp; test"));
        assertEquals("Hex ' apostrophe", GoogleTranslationService.unescapeHtml("Hex &#x27; apostrophe"));
        assertEquals("Normal text without entities", GoogleTranslationService.unescapeHtml("Normal text without entities"));
    }

    @Test
    public void testCooldownHandling() {
        GoogleTranslationService service = new GoogleTranslationService();
        assertTrue(service.isAvailable());
        assertEquals(0, service.getFailureCount());

        service.triggerCooldown();
        assertFalse(service.isAvailable());
        assertEquals(1, service.getFailureCount());

        service.triggerCooldown();
        assertEquals(2, service.getFailureCount());

        service.resetCooldown();
        assertTrue(service.isAvailable());
        assertEquals(0, service.getFailureCount());
    }
}
