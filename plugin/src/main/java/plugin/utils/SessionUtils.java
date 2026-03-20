package plugin.utils;

import java.util.Locale;
import java.util.Map;

import arc.util.Log;
import arc.util.Strings;
import mindustry.Vars;
import mindustry.gen.Iconc;
import mindustry.gen.Player;
import mindustry.type.UnitType;
import mindustry.ui.dialogs.LanguageDialog;
import plugin.Cfg;
import plugin.service.I18n;
import plugin.type.Session;
import plugin.type.SessionData;

public class SessionUtils {

    public static String getPlayerName(Session session) {
        boolean hasColor = session.currentLevel > Cfg.COLOR_NAME_LEVEL || session.player.admin;
        String playerName = hasColor ? session.getData().name : Strings.stripColors(session.getData().name);
        Locale locale = Utils.parseLocale(session.player.locale);
        String language = locale.getDisplayLanguage();

        if (language.isEmpty()) {
            language = session.player.locale;
        }

        return (session.isLoggedIn() ? Iconc.ok : "") + "[white]|" + language + "| " + "[white]<" + "[accent]"
                + session.currentLevel + "[white]> " + playerName;
    }

    public static String getInfoString(Session session, SessionData data) {
        Player player = session.player;
        Locale locale = session.locale;

        StringBuilder info = new StringBuilder();
        long exp = ExpUtils.getTotalExp(data, session.sessionPlayTime());
        int level = ExpUtils.levelFromTotalExp(exp);
        long excess = ExpUtils.excessExp(exp);

        info.append("Player: ").append(player.name)
                .append("\n")
                .append(LanguageDialog.getDisplayName(locale))
                .append("[white]\n")
                .append("[sky]Level: ")
                .append(level)
                .append(" (")
                .append(excess)
                .append("/")
                .append(ExpUtils.expCapOfLevel(level))
                .append(")")
                .append("[white]\n");

        // in millis
        long seconds = (data.playTime + session.sessionPlayTime()) / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;

        info.append("[accent]Play Time: ")
                .append(hours)
                .append("h ")
                .append(minutes % 60)
                .append("m ")
                .append(seconds % 60)
                .append("s")
                .append(" (")
                .append(ExpUtils.playTimeToExp(data.playTime + session.sessionPlayTime()))
                .append("exp)")
                .append("[white]\n");

        for (Map.Entry<Short, Long> entry : data.kills.entrySet()) {
            UnitType unit = Vars.content.unit(entry.getKey());

            if (unit == null) {
                continue;
            }

            if (entry.getValue() == 0) {
                continue;
            }

            try {
                info.append("[white]").append(unit.emoji()).append(": ").append(entry.getValue())
                        .append(" (")
                        .append(ExpUtils.unitHealthToExp(unit.health * entry.getValue()))
                        .append("exp)")
                        .append("\n");
            } catch (Exception e) {
                Log.err("Error while appending kill info for unit @: @", unit.localizedName, e);
            }
        }

        return info.toString();
    }

    public static String getLevelUpMessage(Locale locale, int oldLevel, int newLevel) {
        String message = I18n.t(locale, "Level up");
        return " [green]" + message + Strings.format(" @ -> @", oldLevel, newLevel);
    }

    public static String getKillMessage(Locale locale, String playerName, long count, UnitType unit, long exp) {
        String formatted = Strings.format(" @ @ (+@exp)",
                count,
                unit.emoji(),
                exp);

        return I18n.t(locale, playerName, " ", "@killed", formatted);
    }
}
