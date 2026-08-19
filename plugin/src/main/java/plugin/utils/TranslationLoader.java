package plugin.utils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import arc.util.Log;
import plugin.annotations.Component;
import plugin.annotations.Init;

@Component
public class TranslationLoader {
    private static final String CATALOG_DIR = "i18n";

    @Init
    private void init() {
        Tr.catalog().setLoader(this::loadCatalog);
    }

    private void loadCatalog(String language) {
        String content = readCatalog(language);

        if (content == null) {
            Log.warn("TranslationLoader: could not read catalog '@'", CATALOG_DIR + "/" + language + ".json");
            return;
        }

        Tr.catalog().load(language, content, message -> Log.warn("TranslationLoader: @", message));
    }

    private String readCatalog(String language) {
        try (InputStream stream = TranslationLoader.class.getResourceAsStream(
                "/" + CATALOG_DIR + "/" + language + ".json")) {
            if (stream == null) {
                return null;
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.warn("TranslationLoader: failed to read catalog '@': @", language, e);
            return null;
        }
    }
}