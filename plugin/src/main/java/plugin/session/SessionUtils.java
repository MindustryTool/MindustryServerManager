package plugin.session;

import java.util.Locale;

import arc.util.Strings;
import mindustry.gen.Iconc;
import mindustry.gen.Player;
import mindustry.type.UnitType;
import mindustry.ui.dialogs.LanguageDialog;
import plugin.utils.I18n;

public class SessionUtils {
    public static String getInfoString(Session session, SessionData data) {
        Player player = session.player;
        Locale locale = session.locale;

        StringBuilder info = new StringBuilder();
        long exp = (long) data.exp;
        int level = ExpUtils.levelFromTotalExp(exp);
        long excess = ExpUtils.excessExp(exp);

        if (session.isAdmin()) {
            info.append(Iconc.admin);
        }

        info.append("Player: ").append(player.name);

        if (session.isLoggedIn()) {
            info.append("(")
                    .append(session.login.getName())
                    .append(")");
        }

        info
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
                .append(exp)
                .append("exp)")
                .append("[white]\n");

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
