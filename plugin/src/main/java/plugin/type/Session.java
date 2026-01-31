package plugin.type;

import java.time.Instant;
import java.util.Locale;

import arc.util.Log;
import arc.util.Reflect;
import arc.util.Strings;
import mindustry.Vars;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Iconc;
import mindustry.gen.Player;
import mindustry.net.Administration.PlayerInfo;
import mindustry.type.UnitType;
import plugin.Config;
import plugin.ServerController;
import plugin.handler.ApiGateway;
import plugin.utils.ExpUtils;

public class Session {
    public final Long joinedAt = Instant.now().toEpochMilli();
    public final Locale locale;
    public final Player player;
    public final String originalName;
    public final SessionData data;

    public boolean votedVNW = false;
    public int currentLevel = 0;

    public Session(Player player, SessionData data) {
        this.player = player;
        this.originalName = player.name().replace("\\[[A-Z][A-Z]\\] <[0-9]+>", "");
        this.locale = Locale.forLanguageTag(player.locale().split("_|-")[0]);
        this.data = data;

        update();

        Log.info("Session created for player @: @", player.name, this);
    }

    public void setAdmin(boolean isAdmin) {
        player.admin = isAdmin;
        PlayerInfo target = Vars.netServer.admins.getInfoOptional(player.uuid());

        if (target != null) {
            Player playert = Groups.player.find(p -> p.getInfo() == target);

            if (isAdmin) {
                Vars.netServer.admins.adminPlayer(target.id, playert == null ? target.adminUsid : playert.usid());
            } else {
                Vars.netServer.admins.unAdminPlayer(target.id);
            }
        }
    }

    public void update() {
        var level = ExpUtils.levelFromTotalExp(getExp());

        if (level != currentLevel) {
            ServerController.backgroundTask("Update level", () -> {
                if (currentLevel != 0) {
                    String message = ApiGateway.translate("Level up", locale);

                    Call.sendMessage(
                            player.name + " [green]" + message + Strings.format(" @ => @", currentLevel, level));
                }

                currentLevel = level;

                player.name(getPlayerName(player, level));
            });
        }

    }

    public String getPlayerName(Player player, long level) {
        String[] parts = player.locale.split("-|_");
        String locale = parts.length > 0 ? parts[0] : player.locale;
        boolean hasColor = level > Config.COLOR_NAME_LEVEL || player.admin;
        String playerName = hasColor ? originalName : Strings.stripColors(originalName);

        return "|" + locale.toUpperCase() + "| " + "<" + "[accent]" + level + "[]>" + playerName;
    }

    public void reset() {
        player.name(originalName);
    }

    public void addKill(UnitType unit, int amount) {
        assert amount > 0 : "Kill amount must be greater than 0";

        data.kills.put(unit.id, data.kills.getOrDefault(unit.id, 0L) + amount);
    }

    public long getExp() {
        long exp = (long) (data.kills.entrySet().stream().mapToDouble(entry -> {
            var unit = Vars.content.unit(entry.getKey());

            return unit.health * entry.getValue();
        }).sum() / 500);

        return exp;
    }

    public String info() {
        StringBuilder info = new StringBuilder();
        long exp = getExp();

        info.append("Player: ").append(player.name).append("\n");

        info.append("Level: ").append(ExpUtils.levelFromTotalExp(exp))
                .append(" (")
                .append(exp)
                .append("/")
                .append(ExpUtils.expCapOfLevel(ExpUtils.levelFromTotalExp(exp)))
                .append(")")
                .append("\n");

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
                char icon = Reflect.get(Iconc.class,
                        "unit" + unit.name.substring(0, 1).toUpperCase() + unit.name.substring(1));
                info.append(icon).append(": ").append(entry.getValue()).append("\n");
            } catch (Exception e) {
                Log.err("Error while appending kill info for unit @: @", unit.localizedName, e);
            }
        }

        return info.toString();
    }
}
