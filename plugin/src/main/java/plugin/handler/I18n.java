package plugin.handler;

import java.util.Locale;

import arc.struct.Seq;
import arc.util.Log;

public class I18n {
    public static String t(Locale locale, Object... texts) {
        boolean[] needTranslateIndexes = new boolean[texts.length];

        Seq<String> needTranslate = new Seq<>();

        for (int i = 0; i < texts.length; i++) {
            String str = String.valueOf(texts[i]).trim();
            if (str.startsWith("@")) {
                needTranslateIndexes[i] = true;
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
            var next = texts[i].toString();

            if (needTranslateIndexes[i]) {
                next = translated.remove(0);
            }

            if (sb.length() > 1 && sb.charAt(sb.length() - 1) != ' ' && !next.startsWith(" ")) {
                sb.append(' ');
            }

            sb.append(next);
        }

        return sb.toString();
    }
}
