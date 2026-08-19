package plugin.utils;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class TrCatalog {
    private static final String KEY_PATTERN = "[a-z0-9_]+";

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Map<String, String>> catalogs = new ConcurrentHashMap<>();
    private final Set<String> attempted = ConcurrentHashMap.newKeySet();
    private volatile Consumer<String> loader;

    /**
     * Registers an on-demand catalog loader, invoked at most once per language on
     * the first lookup that needs it.
     */
    public void setLoader(Consumer<String> loader) {
        this.loader = loader;
    }

    public void load(String language, String jsonText, Consumer<String> warning) {
        Map<String, String> entries = new HashMap<>();

        try {
            JsonNode root = mapper.readTree(jsonText);
            if (root != null && root.isObject()) {
                flatten("", root, entries, warning);
            }
        } catch (Exception e) {
            if (warning != null) {
                warning.accept("Failed to parse catalog '" + language + "': " + e.getMessage());
            }
            return;
        }

        catalogs.put(language, entries);
    }

    public boolean hasLanguage(String language) {
        return getEntries(language) != null;
    }

    public Set<String> keys(String language) {
        Map<String, String> entries = getEntries(language);
        return entries == null ? Collections.emptySet() : entries.keySet();
    }

    public String lookup(Locale locale, String key) {
        String tag = locale.toLanguageTag();
        String lang = locale.getLanguage();

        String value = get(tag, key);
        if (value == null && !lang.equals(tag)) {
            value = get(lang, key);
        }
        if (value == null && !"en".equals(lang) && !"en".equals(tag)) {
            value = get("en", key);
        }
        return value;
    }

    public String resolve(Locale locale, String key) {
        String value = lookup(locale, key);
        return value != null ? value : key;
    }

    public String interpolate(String template, Object... args) {
        for (int i = 0; i + 1 < args.length; i += 2) {
            template = template.replace("{" + args[i] + "}", String.valueOf(args[i + 1]));
        }
        return template;
    }

    private String get(String language, String key) {
        Map<String, String> entries = getEntries(language);
        return entries == null ? null : entries.get(key);
    }

    /**
     * Returns the flattened catalog for a language, loading it lazily via the
     * registered loader on first use. A missing/corrupt catalog is attempted
     * only once per language; subsequent lookups fall back through the chain.
     */
    private Map<String, String> getEntries(String language) {
        Map<String, String> entries = catalogs.get(language);
        if (entries == null) {
            Consumer<String> loader = this.loader;
            if (loader != null && attempted.add(language)) {
                loader.accept(language);
                entries = catalogs.get(language);
            }
        }
        return entries;
    }

    private void flatten(String prefix, JsonNode node, Map<String, String> out, Consumer<String> warning) {
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                String segment = entry.getKey();

                if (!segment.matches(KEY_PATTERN)) {
                    if (warning != null) {
                        warning.accept("Invalid key segment '" + segment + "' in '" + prefix + "'");
                    }
                    continue;
                }

                String path = prefix.isEmpty() ? segment : prefix + "." + segment;
                flatten(path, entry.getValue(), out, warning);
            }
        } else if (node.isValueNode()) {
            if (node.isTextual()) {
                out.put(prefix, node.asText());
            } else if (warning != null) {
                warning.accept("Non-string value for key '" + prefix + "'");
            }
        } else if (warning != null) {
            warning.accept("Unsupported node for key '" + prefix + "'");
        }
    }
}