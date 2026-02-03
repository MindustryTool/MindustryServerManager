package plugin.handler;

import java.util.Locale;

import arc.struct.Seq;
import arc.util.Log;

public class I18n {
    public static String t(Locale locale, Object... texts) {
        boolean[] indexes = new boolean[texts.length];

        Seq<String> needTranslate = new Seq<>();

        for (int i = 0; i < texts.length; i++) {
            String str = String.valueOf(texts[i]).trim();
            if (str.startsWith("@")) {
                indexes[i] = true;
                needTranslate.add(str.substring(1));
            }
        }

        Seq<String> translated = null;

        try {
            translated = ApiGateway.translate(needTranslate, locale);
        } catch (Exception e) {
            translated = ApiGateway.translate(translated, Locale.ENGLISH);
            Log.err("Failed to translate texts", e);
        }

        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < texts.length; i++) {
            if (indexes[i]) {
                sb.append(translated.remove(0));
            } else {
                sb.append(texts[i]);
            }
        }

        return sb.toString();
    }
}
