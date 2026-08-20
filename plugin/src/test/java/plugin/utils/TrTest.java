package plugin.utils;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TrTest {
    @Test
    void absentKeyReturnsInterpolatedFallback() {
        Tr.catalog().load("en", "{\"welcome\": {\"message\": \"Welcome\"}}", null);

        assertEquals("Hello acme", Tr.tWithFallback(Locale.ENGLISH, "no.such.key",
                "Hello {server}", "server", "acme"));
    }

    @Test
    void presentKeyWinsOverFallback() {
        Tr.catalog().load("en", "{\"welcome\": {\"message\": \"Welcome\"}}", null);

        assertEquals("Welcome", Tr.tWithFallback(Locale.ENGLISH, "welcome.message", "Fallback"));
    }

    @Test
    void firstLookupResolvesRealClasspathCatalogWithoutComponentInit() throws Exception {
        String json = new String(getClass().getResourceAsStream("/i18n/ko.json").readAllBytes(),
                StandardCharsets.UTF_8);
        TrCatalog expected = new TrCatalog();
        expected.load("ko", json, null);

        assertEquals(expected.lookup(Locale.forLanguageTag("ko"), "hub.not_found"),
                Tr.t(Locale.forLanguageTag("ko"), "hub.not_found"));
    }
}