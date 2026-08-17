package plugin.utils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import arc.util.Log;
import plugin.annotations.Component;
import plugin.annotations.Init;

@Component
public class TranslationLoader {
    private static final String CATALOG_DIR = "i18n";

    @Init
    private void init() {
        Set<String> languages = new LinkedHashSet<>();

        try {
            Enumeration<URL> roots = TranslationLoader.class.getClassLoader().getResources(CATALOG_DIR + "/");
            while (roots.hasMoreElements()) {
                URL root = roots.nextElement();
                languages.addAll(discoverLanguages(root));
            }
        } catch (IOException e) {
            Log.warn("TranslationLoader: failed to enumerate catalogs: @", e);
            return;
        }

        if (languages.isEmpty()) {
            Log.warn("TranslationLoader: no catalog files found in @", CATALOG_DIR);
            return;
        }

        for (String language : languages) {
            String content = readCatalog(language);
            if (content == null) {
                Log.warn("TranslationLoader: could not read catalog '@'", CATALOG_DIR + "/" + language + ".json");
                continue;
            }

            Tr.catalog().load(language, content, message -> Log.warn("TranslationLoader: @", message));
        }
    }

    private List<String> discoverLanguages(URL root) {
        List<String> languages = new ArrayList<>();
        String protocol = root.getProtocol();

        if ("jar".equals(protocol)) {
            try {
                JarURLConnection connection = (JarURLConnection) root.openConnection();
                try (JarFile jar = connection.getJarFile()) {
                    Enumeration<JarEntry> entries = jar.entries();
                    while (entries.hasMoreElements()) {
                        String name = entries.nextElement().getName();
                        if (name.startsWith(CATALOG_DIR + "/") && name.endsWith(".json")) {
                            String fileName = name.substring(CATALOG_DIR.length() + 1);
                            if (fileName.indexOf('/') < 0) {
                                languages.add(fileName.substring(0, fileName.length() - ".json".length()));
                            }
                        }
                    }
                }
            } catch (IOException e) {
                Log.warn("TranslationLoader: failed to list jar catalogs: @", e);
            }
        } else if ("file".equals(protocol)) {
            try {
                File dir = new File(root.toURI());
                File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
                if (files != null) {
                    for (File file : files) {
                        String name = file.getName();
                        languages.add(name.substring(0, name.length() - ".json".length()));
                    }
                }
            } catch (Exception e) {
                Log.warn("TranslationLoader: failed to list file catalogs: @", e);
            }
        }

        return languages;
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