package plugin.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void regionTagDoesNotTriggerLoaderWithRegion() {
        TrCatalog catalog = newCatalog();
        List<String> loaded = new ArrayList<>();
        catalog.setLoader(language -> loaded.add(language));

        assertEquals("Hub not found", catalog.lookup(Locale.forLanguageTag("en-PH"), "hub.not_found"));
        assertTrue(loaded.isEmpty());
    }

    @Test
    void regionTagLoadsBaseLanguageCatalog() {
        TrCatalog catalog = new TrCatalog();
        List<String> loaded = new ArrayList<>();
        catalog.setLoader(language -> {
            loaded.add(language);
            catalog.load(language, "{\"key\": \"lazy " + language + "\"}", null);
        });

        assertEquals("lazy vi", catalog.lookup(Locale.forLanguageTag("vi-VN"), "key"));
        assertEquals(List.of("vi"), loaded);
        assertEquals("lazy zh", catalog.lookup(Locale.forLanguageTag("zh-TW"), "key"));
        assertEquals(List.of("vi", "zh"), loaded);
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
    void malformedJsonWarnsAndRegistersNothing() {
        TrCatalog catalog = new TrCatalog();
        List<String> warnings = new ArrayList<>();
        catalog.load("en", "{not json", warnings::add);

        assertFalse(catalog.hasLanguage("en"));
        assertEquals(1, warnings.size());
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
    void firstLookupTriggersLazyLoad() {
        TrCatalog catalog = new TrCatalog();
        int[] loads = { 0 };
        catalog.setLoader(language -> {
            loads[0]++;
            catalog.load(language, String.format("{\"key\": \"lazy %s\"}", language), null);
        });

        assertEquals("lazy vi", catalog.lookup(Locale.forLanguageTag("vi"), "key"));
        assertEquals(1, loads[0]);
    }

    @Test
    void lazyLoadHappensAtMostOncePerLanguage() {
        TrCatalog catalog = new TrCatalog();
        int[] loads = { 0 };
        catalog.setLoader(language -> {
            loads[0]++;
            catalog.load(language, "{\"key\": \"value\"}", null);
        });

        assertEquals("value", catalog.lookup(Locale.forLanguageTag("vi"), "key"));
        assertEquals("value", catalog.lookup(Locale.forLanguageTag("vi"), "key"));
        assertEquals("value", catalog.lookup(Locale.forLanguageTag("vi"), "key"));
        assertEquals(1, loads[0]);
    }

    @Test
    void failedLazyLoadIsAttemptedOnceAndFallsBack() {
        TrCatalog catalog = new TrCatalog();
        catalog.load("en", "{\"key\": \"english\"}", null);
        int[] loads = { 0 };
        catalog.setLoader(language -> {
            loads[0]++;
        });

        assertEquals("english", catalog.lookup(Locale.forLanguageTag("vi"), "key"));
        assertEquals("english", catalog.lookup(Locale.forLanguageTag("vi"), "key"));
        assertEquals(1, loads[0]);
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
}