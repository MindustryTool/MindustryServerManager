package plugin.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class GoogleWebProviderTest {

    @Test
    public void testParseSingleSentence() throws Exception {
        GoogleWebProvider provider = new GoogleWebProvider();
        String json = "[[[\"Xin chào\", \"Hello\", null, null, 10]], null, \"en\"]";

        TranslationResult result = provider.parseResponse(json);

        assertNotNull(result);
        assertEquals("Xin chào", result.translatedText());
        assertEquals("en", result.sourceLanguage());
    }

    @Test
    public void testParseMultiSegmentResponse() throws Exception {
        GoogleWebProvider provider = new GoogleWebProvider();
        String json = "[[[\"Hello.\", \"Xin chào.\", null, null, 10], [\" How are you?\", \" Bạn khỏe không?\", null, null, 10]], null, \"vi\"]";

        TranslationResult result = provider.parseResponse(json);

        assertNotNull(result);
        assertEquals("Hello. How are you?", result.translatedText());
        assertEquals("vi", result.sourceLanguage());
    }

    @Test
    public void testHtmlEntityUnescaping() {
        assertEquals("It's fine", GoogleWebProvider.unescapeHtml("It&#39;s fine"));
        assertEquals("He said \"yes\" & 'no'", GoogleWebProvider.unescapeHtml("He said &quot;yes&quot; &amp; &#39;no&#39;"));
        assertEquals("<tag> & test", GoogleWebProvider.unescapeHtml("&lt;tag&gt; &amp; test"));
        assertEquals("Hex ' apostrophe", GoogleWebProvider.unescapeHtml("Hex &#x27; apostrophe"));
        assertEquals("Normal text without entities", GoogleWebProvider.unescapeHtml("Normal text without entities"));
    }

    @Test
    public void testCooldownHandling() {
        GoogleWebProvider provider = new GoogleWebProvider();
        assertTrue(provider.isAvailable());

        provider.triggerCooldown();
        assertFalse(provider.isAvailable());

        provider.resetCooldown();
        assertTrue(provider.isAvailable());
    }
}
