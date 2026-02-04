package plugin.type;

import java.time.Instant;
import java.util.Locale;

import arc.Core;
import arc.util.Log;
import arc.util.Strings;
import mindustry.Vars;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.net.Administration.PlayerInfo;
import mindustry.type.UnitType;
import mindustry.ui.dialogs.LanguageDialog;
import plugin.Config;
import plugin.ServerControl;
import plugin.handler.I18n;
import plugin.repository.SessionRepository;
import plugin.utils.ExpUtils;
import plugin.utils.Utils;

public class Session {
    public final Locale locale;
    public final Player player;
    public final String originalName;
    public final Long joinedAt = Instant.now().toEpochMilli();

    public boolean votedVNW = false;
    public boolean votedGrief = false;
    public int currentLevel = 0;

    public Session(Player player) {
        this.player = player;
        this.originalName = player.name().replaceAll("\\|[a-zA-Z]+\\| \\[\\]<\\[[^]]+\\]\\d+>", "").trim();
        this.locale = Locale.forLanguageTag(player.locale().replace("_", "-"));

        Log.info("Session created for player @: @", player.name, this);
    }

    public void setAdmin(boolean isAdmin) {
        Core.app.post(() -> {
            player.admin = isAdmin;

            if (isAdmin) {
                Utils.forEachPlayerLocale((locale, players) -> {
                    String msg = I18n.t(locale, "@An admin logged in:[] ", player.name);
                    for (var p : players) {
                        p.sendMessage(msg);
                    }
                });
            }

            PlayerInfo target = Vars.netServer.admins.getInfoOptional(player.uuid());

            if (target != null) {
                Player playert = Groups.player.find(p -> p.getInfo() == target);

                if (isAdmin) {
                    Vars.netServer.admins.adminPlayer(target.id, playert == null ? target.adminUsid : playert.usid());
                } else {
                    Vars.netServer.admins.unAdminPlayer(target.id);
                }
            } else {
                player.admin = false;
                Log.info("Player @ is no longer an admin", player.name);
            }
        });
    }

    public void update() {
        var level = ExpUtils.levelFromTotalExp(ExpUtils.getTotalExp(data(), sessionPlayTime()));

        if (level != currentLevel) {
            if (currentLevel != 0) {
                var oldLevel = currentLevel;
                var newLevel = level;

                ServerControl.backgroundTask("Update level", () -> {
                    Utils.forEachPlayerLocale((locale, players) -> {
                        String message = I18n.t(locale, "Level up");

                        players.forEach(player -> player.sendMessage(
                                this.player.name + " [green]" + message
                                        + Strings.format(" @ -> @", oldLevel, newLevel)));
                    });
                });
            }

            currentLevel = level;

            player.name(getPlayerName(level));
        }

    }

    public String getPlayerName(long level) {
        boolean hasColor = level > Config.COLOR_NAME_LEVEL || player.admin;
        String playerName = hasColor ? originalName : Strings.stripColors(originalName);
        String language = locale.getDisplayLanguage();

        if (language.isEmpty()) {
            language = player.locale;
        }

        return "[]|" + language + "| " + "[]<" + "[accent]" + level + "[]> " + playerName;
    }

    public void reset() {
        player.name(originalName);
    }

    public SessionData data() {
        return SessionRepository.get(player);
    }

    public long sessionPlayTime() {
        return Instant.now().toEpochMilli() - joinedAt;
    }

    public synchronized long addKill(UnitType unit, int amount) {
        assert amount > 0 : "Kill amount must be greater than 0";
        var data = data();
        var value = data.kills.getOrDefault(unit.id, 0L) + amount;

        data.kills.put(unit.id, value);

        return value;
    }

    public String info() {
        var data = data();

        StringBuilder info = new StringBuilder();
        long exp = ExpUtils.getTotalExp(data, sessionPlayTime());
        int level = ExpUtils.levelFromTotalExp(exp);
        long excess = ExpUtils.excessExp(exp);

        info.append("Player: ").append(player.name)
                .append("\n")
                .append(LanguageDialog.getDisplayName(locale))
                .append("[]\n")
                .append("[sky]Level: ")
                .append(level)
                .append(" (")
                .append(excess)
                .append("/")
                .append(ExpUtils.expCapOfLevel(level))
                .append(")")
                .append("[]\n");

        // in millis
        long seconds = (data.playTime + sessionPlayTime()) / 1000;
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
                .append(ExpUtils.playTimeToExp(data.playTime + sessionPlayTime()))
                .append("exp)")
                .append("[]\n");

        for (var entry : data.kills.entrySet()) {
            var unit = Vars.content.unit(entry.getKey());

            if (unit == null) {
                Log.info("Unit @ is null", entry.getKey());
                continue;
            }

            if (entry.getValue() == 0) {
                continue;
            }

            try {
                char icon = Utils.icon(unit);
                info.append("[]").append(icon).append(": ").append(entry.getValue())
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

    @Override
    public String toString() {
        return "Session<" + player.uuid() + ":" + player.name + ">";
    }
}
