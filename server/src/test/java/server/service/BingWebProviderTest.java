package server.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import common.translation.TranslationResponse;
import server.service.translation.TranslationException;
import server.service.translation.provider.BingWebProvider;

public class BingWebProviderTest {

    @Test
    public void testMetadata() {
        BingWebProvider provider = new BingWebProvider();
        assertEquals("bing-web", provider.name());
        assertTrue(provider.isAvailable());
    }

    @Test
    public void testExtractCredentials() {
        BingWebProvider provider = new BingWebProvider();
        String html = """
                <html>
                <head>
                <script>
                var IG="SAMPLE_IG_12345";
                </script>
                </head>
                <body data-iid="translator.5023">
                <script>
                params_AbusePreventionHelper = [1791541570891,"sample_token_abc",3600000];
                </script>
                </body>
                </html>
                """;

        BingWebProvider.SessionCredentials creds = provider.extractCredentials(html);
        assertNotNull(creds);
        assertEquals("SAMPLE_IG_12345", creds.ig());
        assertEquals("translator.5023", creds.iid());
        assertEquals("1791541570891", creds.key());
        assertEquals("sample_token_abc", creds.token());
        assertFalse(creds.isExpired());
    }

    @Test
    public void testParseSuccessfulResponse() throws Exception {
        BingWebProvider provider = new BingWebProvider();
        String json = """
                [{"translations":[{"text":"Xin ch&#224;o th&#7871; gi&#7899;i!","to":"vi"}],"usedLLM":true,"detectedLanguage":{"language":"en"}}]
                """;

        TranslationResponse response = provider.parseResponse(json);
        assertNotNull(response);
        assertEquals("Xin chào thế giới!", response.getTranslatedText());
        assertEquals("en", response.getSourceLanguage());
    }

    @Test
    public void testParseExpiredResponseThrowsSessionExpiredException() {
        BingWebProvider provider = new BingWebProvider();
        String json = "{\"statusCode\":205,\"errorMessage\":\"\"}";

        assertThrows(BingWebProvider.SessionExpiredException.class, () -> {
            provider.parseResponse(json);
        });
    }

    @Test
    public void testParseInvalidFormatThrowsException() {
        BingWebProvider provider = new BingWebProvider();
        assertThrows(TranslationException.Permanent.class, () -> {
            provider.parseResponse("[]");
        });
        assertThrows(TranslationException.Permanent.class, () -> {
            provider.parseResponse("[{\"translations\":[]}]");
        });
    }

    @Test
    public void testHtmlEntityUnescaping() {
        assertEquals("It's fine", BingWebProvider.unescapeHtml("It&#39;s fine"));
        assertEquals("He said \"yes\" & 'no'", BingWebProvider.unescapeHtml("He said &quot;yes&quot; &amp; &#39;no&#39;"));
        assertEquals("<tag> & test", BingWebProvider.unescapeHtml("&lt;tag&gt; &amp; test"));
        assertEquals("Hex ' apostrophe", BingWebProvider.unescapeHtml("Hex &#x27; apostrophe"));
        assertEquals("Normal text without entities", BingWebProvider.unescapeHtml("Normal text without entities"));
    }

    @Test
    public void testTranslateBlankReturnsNull() throws Exception {
        BingWebProvider provider = new BingWebProvider();
        assertNull(provider.translate(null, "vi"));
        assertNull(provider.translate("", "vi"));
        assertNull(provider.translate("   ", "vi"));
        assertNull(provider.translate("hello", null));
        assertNull(provider.translate("hello", ""));
    }
}
