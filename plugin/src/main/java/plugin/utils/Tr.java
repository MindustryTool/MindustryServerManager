package plugin.utils;

import java.util.Locale;

import mindustry.gen.Player;
import plugin.session.Session;

public class Tr {
    private static final TrCatalog CATALOG = new TrCatalog();

    private Tr() {
    }

    public static TrCatalog catalog() {
        return CATALOG;
    }

    public static String t(Locale locale, String key, Object... args) {
        return CATALOG.interpolate(CATALOG.resolve(locale, key), args);
    }

    public static String tWithFallback(Locale locale, String key, Object fallbackText, Object... args) {
        String value = CATALOG.lookup(locale, key);
        return CATALOG.interpolate(value != null ? value : String.valueOf(fallbackText), args);
    }

    public static String t(Session session, String key, Object... args) {
        return t(session.locale, key, args);
    }

    public static String t(Player player, String key, Object... args) {
        return t(Utils.parseLocale(player.locale), key, args);
    }
}