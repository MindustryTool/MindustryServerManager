package plugin.handler;

import java.util.Locale;

import arc.struct.Seq;
import arc.util.Log;
import arc.util.Strings;

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
            Log.err(e.getMessage());
        }

        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < texts.length; i++) {
            var next = texts[i].toString();

            if (needTranslateIndexes[i]) {
                next = translated.remove(0);
            }

            boolean prevHasSpace = sb.length() > 1 && sb.charAt(sb.length() - 1) == ' ';
            boolean nextHasSpace = next.startsWith(" ");
            boolean prevIsColor = Strings.stripColors(sb.toString()).trim().isEmpty();

            if (!prevHasSpace && !nextHasSpace && !prevIsColor) {
                sb.append(' ');
            }

            sb.append(next);
        }

        return sb.toString();
    }
}
