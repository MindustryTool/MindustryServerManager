package server.service;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import common.translation.TranslationResponse;
import server.service.translation.provider.GoogleWebProvider;

import static org.junit.jupiter.api.Assertions.*;

public class GoogleWebProviderTest {

    @Test
    public void testDirectProviderMetadata() {
        GoogleWebProvider provider = new GoogleWebProvider();
        assertEquals("google-web", provider.name());
        assertFalse(provider.isProxied());
        assertTrue(provider.isAvailable());
    }

    @Test
    public void testProxiedProviderMetadata() {
        MultiSourceProxyPool pool = new MultiSourceProxyPool(Collections.emptyList());
        GoogleWebProvider provider = new GoogleWebProvider(pool);
        assertEquals("google-web-proxy", provider.name());
        assertTrue(provider.isProxied());
        assertTrue(provider.isAvailable());
    }

    @Test
    public void testParseSingleSentence() throws Exception {
        GoogleWebProvider provider = new GoogleWebProvider();
        String json = "[[[\"Xin chào\", \"Hello\", null, null, 10]], null, \"en\"]";

        TranslationResponse result = provider.parseResponse(json);

        assertNotNull(result);
        assertEquals("Xin chào", result.getTranslatedText());
        assertEquals("en", result.getSourceLanguage());
    }

    @Test
    public void testParseMultiSegmentResponse() throws Exception {
        GoogleWebProvider provider = new GoogleWebProvider();
        String json = "[[[\"Hello.\", \"Xin chào.\", null, null, 10], [\" How are you?\", \" Bạn khỏe không?\", null, null, 10]], null, \"vi\"]";

        TranslationResponse result = provider.parseResponse(json);

        assertNotNull(result);
        assertEquals("Hello. How are you?", result.getTranslatedText());
        assertEquals("vi", result.getSourceLanguage());
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
    public void testProviderIsAlwaysAvailableByDefault() {
        // Cooldown is now managed centrally by TranslationService.ProviderState
        GoogleWebProvider provider = new GoogleWebProvider();
        assertTrue(provider.isAvailable(), "Provider should always report available (cooldown managed centrally)");
    }

    @Test
    public void testProxiedFailsFastWhenNoProxiesAvailable() {
        MultiSourceProxyPool emptyPool = new MultiSourceProxyPool(Collections.emptyList());
        GoogleWebProvider provider = new GoogleWebProvider(emptyPool);

        assertThrows(IllegalStateException.class, () -> provider.translate("hello", "vi"));
    }

    @Test
    public void testProxiedEvictionAfterRepeatedFailures() {
        MultiSourceProxyPool pool = new MultiSourceProxyPool(Collections.emptyList());

        InetSocketAddress deadProxy = new InetSocketAddress("127.0.0.1", 59999);
        pool.addProxies(List.of(deadProxy));
        assertEquals(1, pool.size());

        GoogleWebProvider provider = new GoogleWebProvider(pool);

        assertThrows(Exception.class, () -> provider.translate("test", "en"));

        assertEquals(0, pool.size());
    }
}
