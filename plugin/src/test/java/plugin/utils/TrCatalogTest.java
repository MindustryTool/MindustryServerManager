package plugin.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TrCatalogTest {
    private TrCatalog newCatalog() {
        TrCatalog catalog = new TrCatalog();
        catalog.load("en", "{"
                + "  \"hub\": {"
                + "    \"not_found\": \"Hub not found\","
                + "    \"global\": {"
                + "      \"players\": \"[#32CD32]Players: {players}\","
                + "      \"map\": \"Map: {map}\""
                + "    }"
                + "  },"
                + "  \"vote\": {"
                + "    \"failed\": \"[scarlet]Vote failed.\","
                + "    \"timeout\": \"Vote timeout in {time} seconds.\""
                + "  }"
                + "}", null);
        return catalog;
    }

    @Test
    void loadsAndFlattensNestedKeys() {
        TrCatalog catalog = newCatalog();

        assertEquals("Hub not found", catalog.lookup(Locale.ENGLISH, "hub.not_found"));
        assertEquals("[#32CD32]Players: {players}", catalog.lookup(Locale.ENGLISH, "hub.global.players"));
    }

    @Test
    void invalidKeySegmentsAreSkippedWithWarning() {
        TrCatalog catalog = new TrCatalog();
        List<String> warnings = new ArrayList<>();
        catalog.load("en", "{\"Bad Key\": \"x\", \"valid\": \"ok\", \"module\": {\"Mixed_Case\": \"y\"}}", warnings::add);

        assertEquals("ok", catalog.lookup(Locale.ENGLISH, "valid"));
        assertEquals(null, catalog.lookup(Locale.ENGLISH, "Bad Key"));
        assertEquals(null, catalog.lookup(Locale.ENGLISH, "module.Mixed_Case"));
        assertEquals(2, warnings.size());
        assertTrue(warnings.get(0).contains("Bad Key"));
        assertTrue(warnings.get(1).contains("Mixed_Case"));
    }

    @Test
    void regionTagFallsBackToBaseLanguage() {
        TrCatalog catalog = newCatalog();

        assertEquals("Hub not found", catalog.lookup(Locale.forLanguageTag("en-US"), "hub.not_found"));
    }

    @Test
    void regionTagLoadsBaseLanguageCatalogOnDemand() {
        TrCatalog catalog = new TrCatalog();

        String vi = catalog.lookup(Locale.forLanguageTag("vi-VN"), "hub.not_found");
        assertNotNull(vi);
        assertFalse(vi.equals("hub.not_found"));
        assertTrue(catalog.hasLanguage("vi"));

        String zh = catalog.lookup(Locale.forLanguageTag("zh-TW"), "hub.not_found");
        assertNotNull(zh);
        assertFalse(zh.equals("hub.not_found"));
        assertTrue(catalog.hasLanguage("zh"));
    }

    @Test
    void unknownLocaleFallsBackToEnglish() {
        TrCatalog catalog = newCatalog();

        assertEquals("Hub not found", catalog.lookup(Locale.forLanguageTag("xx"), "hub.not_found"));
        assertEquals("Hub not found", catalog.lookup(new Locale("xx"), "hub.not_found"));
    }

    @Test
    void missingKeyReturnsRawKey() {
        TrCatalog catalog = newCatalog();

        assertEquals("no.such.key", catalog.resolve(Locale.ENGLISH, "no.such.key"));
        assertEquals(null, catalog.lookup(Locale.ENGLISH, "no.such.key"));
    }

    @Test
    void interpolatesNamedPlaceholdersPreservingColorCodes() {
        TrCatalog catalog = newCatalog();

        String result = catalog.interpolate(
                catalog.resolve(Locale.ENGLISH, "hub.global.players"),
                "players", 12);
        assertEquals("[#32CD32]Players: 12", result);
    }

    @Test
    void interpolatesMultiplePlaceholders() {
        TrCatalog catalog = newCatalog();

        String result = catalog.interpolate(
                catalog.resolve(Locale.ENGLISH, "vote.timeout"),
                "time", 30);
        assertEquals("Vote timeout in 30 seconds.", result);
    }

    @Test
    void unknownLocalePrefersCatalogOverRawKey() {
        TrCatalog catalog = newCatalog();
        catalog.load("es", "{\"vote\": {\"failed\": \"[scarlet]Vote failed.\"}}", null);

        assertEquals("[scarlet]Vote failed.", catalog.lookup(new Locale("es"), "vote.failed"));
    }

    @Test
    void nonStringValuesWarn() {
        TrCatalog catalog = new TrCatalog();
        List<String> warnings = new ArrayList<>();
        catalog.load("en", "{\"count\": 3}", warnings::add);

        assertEquals(null, catalog.lookup(Locale.ENGLISH, "count"));
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("count"));
    }

    @Test
    void malformedJsonWarnsAndDoesNotClobberExistingCatalog() {
        TrCatalog catalog = new TrCatalog();
        catalog.load("en", "{\"key\": \"english\"}", null);
        List<String> warnings = new ArrayList<>();

        catalog.load("en", "{not json", warnings::add);

        assertEquals(1, warnings.size());
        assertEquals("english", catalog.lookup(Locale.ENGLISH, "key"));
    }

    @Test
    void perLanguageIsolation() {
        TrCatalog catalog = new TrCatalog();
        catalog.load("en", "{\"key\": \"english\"}", null);
        catalog.load("vi", "{\"key\": \"tieng viet\"}", null);

        assertEquals("english", catalog.lookup(Locale.ENGLISH, "key"));
        assertEquals("tieng viet", catalog.lookup(Locale.forLanguageTag("vi"), "key"));
        assertTrue(catalog.hasLanguage("vi"));
        assertTrue(catalog.hasLanguage("en"));
    }

    @Test
    void firstLookupTriggersOnDemandLoad() {
        TrCatalog catalog = new TrCatalog();

        String result = catalog.lookup(Locale.forLanguageTag("vi"), "hub.not_found");

        assertFalse(result.equals("hub.not_found"));
        assertTrue(catalog.hasLanguage("vi"));
    }

    @Test
    void onDemandLoadHappensAtMostOncePerLanguage() {
        TrCatalog catalog = new TrCatalog();

        String first = catalog.lookup(Locale.forLanguageTag("vi"), "hub.not_found");
        String second = catalog.lookup(Locale.forLanguageTag("vi"), "hub.not_found");
        String third = catalog.lookup(Locale.forLanguageTag("vi"), "hub.not_found");

        assertEquals(first, second);
        assertEquals(second, third);
        assertTrue(catalog.hasLanguage("vi"));
    }

    @Test
    void missingCatalogFallsBackAndRetriesNextLookup() {
        TrCatalog catalog = new TrCatalog();
        catalog.load("en", "{\"key\": \"english\"}", null);

        assertEquals("english", catalog.lookup(Locale.forLanguageTag("xx"), "key"));
        assertEquals("english", catalog.lookup(Locale.forLanguageTag("xx"), "key"));
        assertFalse(catalog.hasLanguage("xx"));
    }

    @Test
    void loadValidatesAgainstKeyPattern() {
        TrCatalog catalog = new TrCatalog();
        List<String> warnings = new ArrayList<>();
        catalog.load("en", "{\"UPPER\": \"x\", \"with-hyphen\": \"y\"}", warnings::add);

        assertEquals(null, catalog.lookup(Locale.ENGLISH, "UPPER"));
        assertEquals(null, catalog.lookup(Locale.ENGLISH, "with-hyphen"));
        assertEquals(2, warnings.size());
    }

    @Test
    void realEnglishCatalogLoadsCleanAndResolves() throws Exception {
        TrCatalog catalog = new TrCatalog();
        List<String> warnings = new ArrayList<>();
        String json = new String(getClass().getResourceAsStream("/i18n/en.json").readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        catalog.load("en", json, warnings::add);

        assertTrue(warnings.isEmpty(), "en.json produced validation warnings: " + warnings);
        assertTrue(catalog.hasLanguage("en"));
        assertEquals("Hub not found", catalog.lookup(Locale.ENGLISH, "hub.not_found"));
        assertEquals("[#32CD32]Players: {players}", catalog.lookup(Locale.ENGLISH, "hub.global.players"));
        assertEquals("Cick to where you want to start", catalog.lookup(Locale.ENGLISH, "catali.click_to_start"));
        assertEquals("Leadership transferred to {name}", catalog.lookup(Locale.ENGLISH, "catali.leadership_transferred"));
        assertEquals("No team", catalog.lookup(Locale.ENGLISH, "catali.no_team"));
        assertEquals("You already have a team!", catalog.lookup(Locale.ENGLISH, "catali.already_have_team"));
    }

    @Test
    void vietnameseCatalogOverridesEnglishPerLocale() throws Exception {
        TrCatalog catalog = new TrCatalog();
        List<String> warnings = new ArrayList<>();
        catalog.load("en", new String(getClass().getResourceAsStream("/i18n/en.json").readAllBytes(), java.nio.charset.StandardCharsets.UTF_8), warnings::add);
        catalog.load("vi", new String(getClass().getResourceAsStream("/i18n/vi.json").readAllBytes(), java.nio.charset.StandardCharsets.UTF_8), warnings::add);

        assertTrue(warnings.isEmpty(), "catalogs produced validation warnings: " + warnings);
        assertTrue(catalog.hasLanguage("vi"));

        Locale vi = Locale.forLanguageTag("vi");
        assertEquals("Không tìm thấy máy chủ", catalog.lookup(vi, "hub.not_found"));
        assertEquals("[#32CD32]Người chơi: {players}", catalog.lookup(vi, "hub.global.players"));

        Locale viVN = Locale.forLanguageTag("vi-VN");
        assertEquals("Không tìm thấy máy chủ", catalog.lookup(viVN, "hub.not_found"));

        assertEquals("Hub not found", catalog.lookup(Locale.forLanguageTag("en-US"), "hub.not_found"));
        assertEquals("Hub not found", catalog.lookup(new Locale("xx"), "hub.not_found"));
        assertEquals("Hiện không có bản đồ nào đang được bỏ phiếu.", catalog.lookup(vi, "vote.no_map_voted"));
        assertEquals("No map is currently being voted on.", catalog.lookup(new Locale("xx"), "vote.no_map_voted"));
        assertEquals("no.such.key", catalog.resolve(vi, "no.such.key"));
    }

    @Test
    void allCatalogsLoadCleanlyAndTranslateMessages() throws Exception {
        String[] languages = {"ar", "en", "es", "id", "ja", "ko", "pl", "ru", "th", "vi", "zh"};
        TrCatalog catalog = new TrCatalog();

        for (String lang : languages) {
            List<String> warnings = new ArrayList<>();
            var stream = getClass().getResourceAsStream("/i18n/" + lang + ".json");
            org.junit.jupiter.api.Assertions.assertNotNull(stream, "Missing resource for: " + lang);
            String json = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            catalog.load(lang, json, warnings::add);

            assertTrue(warnings.isEmpty(), lang + ".json produced validation warnings: " + warnings);
            assertTrue(catalog.hasLanguage(lang));

            Locale locale = Locale.forLanguageTag(lang);
            String chooseServer = catalog.lookup(locale, "hub.choose_server");
            String welcomeMsg = catalog.lookup(locale, "welcome.message");

            org.junit.jupiter.api.Assertions.assertNotNull(chooseServer, "hub.choose_server missing in " + lang);
            org.junit.jupiter.api.Assertions.assertNotNull(welcomeMsg, "welcome.message missing in " + lang);
            assertFalse(chooseServer.equals("{text}"), "hub.choose_server was not translated in " + lang);
            assertFalse(welcomeMsg.equals("{text}"), "welcome.message was not translated in " + lang);
        }
    }
}