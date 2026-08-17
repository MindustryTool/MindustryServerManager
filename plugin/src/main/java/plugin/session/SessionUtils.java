package plugin.session;

import java.util.Locale;

import mindustry.gen.Iconc;
import mindustry.gen.Player;
import mindustry.type.UnitType;
import mindustry.ui.dialogs.LanguageDialog;
import plugin.utils.Tr;

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

        info.append(Tr.t(session, "session.player", "name", player.name));

        if (session.isLoggedIn()) {
            info.append("(")
                    .append(session.login.getName())
                    .append(")");
        }

        info
                .append("\n")
                .append(LanguageDialog.getDisplayName(locale))
                .append("[white]\n")
                .append(Tr.t(session, "session.level", "level", level, "excess", excess, "cap",
                        ExpUtils.expCapOfLevel(level)))
                .append("[white]\n");

        // in millis
        long seconds = (data.playTime + session.sessionPlayTime()) / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;

        info.append(Tr.t(session, "session.play_time", "hours", hours, "minutes", minutes % 60, "seconds", seconds % 60,
                "exp", exp))
                .append("[white]\n");

        return info.toString();
    }

    public static String getLevelUpMessage(Locale locale, int oldLevel, int newLevel) {
        return Tr.t(locale, "session.level_up", "old", oldLevel, "new", newLevel);
    }

    public static String getKillMessage(Locale locale, String playerName, long count, UnitType unit, long exp) {
        return Tr.t(locale, "session.killed",
                "player", playerName, "count", count, "unit", unit.emoji(), "exp", exp);
    }
}
