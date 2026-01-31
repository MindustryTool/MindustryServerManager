package plugin.type;

import java.time.Instant;
import java.util.Locale;

import mindustry.Vars;
import mindustry.gen.Groups;
import mindustry.gen.Iconc;
import mindustry.gen.Player;
import mindustry.net.Administration.PlayerInfo;

public class Session {
    public final Long joinedAt = Instant.now().toEpochMilli();
    public final Locale locale;
    public final Player player;
    public final String originalName;

    public final SessionData data;

    public boolean votedVNW = false;

    public Session(Player player, SessionData data) {
        this.player = player;
        this.originalName = player.name();
        this.locale = Locale.forLanguageTag(player.locale().split("_|-")[0]);
        this.data = data;

        updatePlayerName();
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

    public void updatePlayerName() {
        player.name(getPlayerName(player, 0));
    }

    public static String getPlayerName(Player player, long level) {
        String[] parts = player.locale.split("-|_");
        String locale = parts.length > 0 ? parts[0] : player.locale;

        return "[" + locale.toUpperCase() + "] " + Iconc.leftOpen + "[accent]" + level + Iconc.rightOpen + "[] "
                + player.name;
    }

    public void reset() {
        player.name(originalName);
    }

    public void save() {

    }
}
