package plugin.type;

import java.time.Instant;
import java.util.Locale;

import arc.Core;
import arc.util.Log;
import arc.util.Reflect;
import arc.util.Strings;
import mindustry.Vars;
import mindustry.content.UnitTypes;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Iconc;
import mindustry.gen.Player;
import mindustry.net.Administration.PlayerInfo;
import mindustry.type.UnitType;
import plugin.Config;
import plugin.ServerControl;
import plugin.handler.ApiGateway;
import plugin.utils.ExpUtils;
import plugin.utils.Utils;

public class Session {
    public final Long joinedAt = Instant.now().toEpochMilli();
    public final Locale locale;
    public final Player player;
    public final String originalName;
    public final SessionData data;

    public boolean votedVNW = false;
    public boolean votedGrief = false;
    public int currentLevel = 0;
    public Instant lastUpdate = Instant.now();

    public Session(Player player, SessionData data) {
        this.player = player;
        this.originalName = player.name().replaceAll("\\|[A-Z]{2}\\| <\\[[^]]+\\]\\d+>", "").trim();
        this.locale = Locale.forLanguageTag(player.locale().split("_|-")[0]);
        this.data = data;

        Log.info("Session created for player @: @", player.name, this);

        update();
    }

    public void setAdmin(boolean isAdmin) {
        Core.app.post(() -> {
            player.admin = isAdmin;

            if (isAdmin) {
                Call.sendMessage("[accent]An admin logged in:[] " + player.name);
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
        data.playTime += Instant.now().toEpochMilli() - lastUpdate.toEpochMilli();
        lastUpdate = Instant.now();
        var level = ExpUtils.levelFromTotalExp(getExp());

        if (level != currentLevel) {
            if (currentLevel != 0) {
                var oldLevel = currentLevel;
                var newLevel = level;

                ServerControl.backgroundTask("Update level", () -> {
                    Utils.forEachPlayerLocale((locale, players) -> {
                        String message = ApiGateway.translate("Level up", locale);

                        players.forEach(player -> player.sendMessage(
                                this.player.name + " [green]" + message
                                        + Strings.format(" @ -> @", oldLevel, newLevel)));
                    });
                });
            }

            currentLevel = level;

            player.name(getPlayerName(player, level));
        }

    }

    public String getPlayerName(Player player, long level) {
        String[] parts = player.locale.split("-|_");
        String locale = parts.length > 0 ? parts[0] : player.locale;
        boolean hasColor = level > Config.COLOR_NAME_LEVEL || player.admin;
        String playerName = hasColor ? originalName : Strings.stripColors(originalName);

        return "|" + locale.toUpperCase() + "| " + "<" + "[accent]" + level + "[]> " + playerName;
    }

    public void reset() {
        player.name(originalName);
    }

    public void addKill(UnitType unit, int amount) {
        assert amount > 0 : "Kill amount must be greater than 0";

        data.kills.put(unit.id, data.kills.getOrDefault(unit.id, 0L) + amount);
    }

    public long getExp() {
        long exp = unitHealthToExp(data.kills.entrySet().stream().mapToDouble(entry -> {
            var unit = Vars.content.unit(entry.getKey());

            return unit.health * entry.getValue();
        }).sum());

        exp += playTimeToExp(data.playTime);

        return exp;
    }

    private long unitHealthToExp(double health) {
        return (long) (health / UnitTypes.flare.health / 2);
    }

    // Killing 1 flare = 5 seconds of play time
    private long playTimeToExp(long playTime) {
        return (long) (playTime / UnitTypes.flare.health / 5 / 10);
    }

    public String info() {
        StringBuilder info = new StringBuilder();
        long exp = getExp();
        int level = ExpUtils.levelFromTotalExp(exp);
        long excess = ExpUtils.excessExp(exp);

        info.append("Player: ").append(player.name)
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
        long seconds = data.playTime / 1000;
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
                .append(playTimeToExp(data.playTime))
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
                char icon = Reflect.get(Iconc.class, "unit" + Strings.capitalize(unit.name));
                info.append("[]").append(icon).append(": ").append(entry.getValue())
                        .append(" (")
                        .append(unitHealthToExp(unit.health * entry.getValue()))
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
