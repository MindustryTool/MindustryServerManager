package plugin.view;

import java.util.Locale;
import java.util.Map;

import arc.util.Log;
import arc.util.Strings;
import mindustry.Vars;
import mindustry.gen.Player;
import mindustry.type.UnitType;
import mindustry.ui.dialogs.LanguageDialog;
import plugin.Config;
import plugin.service.I18n;
import plugin.type.Session;
import plugin.type.SessionData;
import plugin.utils.ExpUtils;
import plugin.utils.Utils;

public class SessionView {

    public static String getPlayerName(Player player, SessionData data, long level) {
        boolean hasColor = level > Config.COLOR_NAME_LEVEL || player.admin;
        String playerName = hasColor ? data.name : Strings.stripColors(data.name);
        Locale locale = Utils.parseLocale(player.locale);
        String language = locale.getDisplayLanguage();

        if (language.isEmpty()) {
            language = player.locale;
        }

        return "[white]|" + language + "| " + "[white]<" + "[accent]" + level + "[white]> " + playerName;
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
                char icon = Utils.icon(unit);
                info.append("[white]").append(icon).append(": ").append(entry.getValue())
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

    public static String getAdminLoginMessage(Locale locale, String playerName) {
        return I18n.t(locale, "@An admin logged in:[white] ", playerName);
    }
    
    public static String getKillMessage(Locale locale, String playerName, long count, UnitType unit, long exp) {
         String formatted = Strings.format(" @ @ (+@exp)",
                count,
                Utils.icon(unit),
                exp);

        return I18n.t(locale, playerName, " ", "@killed", formatted);
    }
}
