package plugin.utils;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class TrCatalog {
    private static final String KEY_PATTERN = "[a-z0-9_]+";

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Map<String, String>> catalogs = new HashMap<>();

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
        return catalogs.containsKey(language);
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
        Map<String, String> entries = catalogs.get(language);
        return entries == null ? null : entries.get(key);
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