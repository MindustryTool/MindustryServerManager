package plugin.utils;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class LocaleKeyParityTest {
    private static final String BASE_LANGUAGE = "en";

    @Test
    void everyCatalogMatchesEnglishKeySet() throws IOException, URISyntaxException {
        Map<String, String> files = enumerateLocaleFiles();

        assertTrue(files.containsKey(BASE_LANGUAGE), "baseline " + BASE_LANGUAGE + ".json not found under i18n/");

        Set<String> baseKeys = loadKeys(BASE_LANGUAGE, files.get(BASE_LANGUAGE), new ArrayList<>());

        for (Map.Entry<String, String> entry : files.entrySet()) {
            String language = entry.getKey();
            if (language.equals(BASE_LANGUAGE)) {
                continue;
            }

            List<String> warnings = new ArrayList<>();
            Set<String> keys = loadKeys(language, entry.getValue(), warnings);
            assertTrue(warnings.isEmpty(),
                    language + ".json produced validation warnings: " + warnings);
            assertTrue(keys.equals(baseKeys),
                    language + ".json keys differ from " + BASE_LANGUAGE + ".json\n"
                            + "  missing: " + difference(baseKeys, keys) + "\n"
                            + "  extra:   " + difference(keys, baseKeys));
        }
    }

    private Map<String, String> enumerateLocaleFiles() throws IOException, URISyntaxException {
        Map<String, String> files = new TreeMap<>();
        ClassLoader loader = getClass().getClassLoader();
        Enumeration<URL> resources = loader.getResources("i18n");
        while (resources.hasMoreElements()) {
            URL url = resources.nextElement();
            if (!"file".equals(url.getProtocol())) {
                continue;
            }
            File dir = new File(url.toURI());
            File[] jsonFiles = dir.listFiles((d, name) -> name.endsWith(".json"));
            if (jsonFiles == null) {
                continue;
            }
            for (File file : jsonFiles) {
                String name = file.getName();
                String language = name.substring(0, name.length() - ".json".length());
                files.put(language, new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            }
        }
        return files;
    }

    private Set<String> loadKeys(String language, String jsonText, List<String> warnings) {
        TrCatalog catalog = new TrCatalog();
        catalog.load(language, jsonText, warnings::add);
        return catalog.hasLanguage(language) ? catalog.keys(language) : Collections.emptySet();
    }

    private Set<String> difference(Set<String> a, Set<String> b) {
        Set<String> result = new TreeSet<>(a);
        result.removeAll(b);
        return result;
    }
}
