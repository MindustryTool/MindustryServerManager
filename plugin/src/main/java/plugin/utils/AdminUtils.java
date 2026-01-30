package plugin.utils;

import arc.util.Log;
import dto.LoginDto;
import mindustry.Vars;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.net.Administration.PlayerInfo;

public class AdminUtils {
    public static void setPlayerData(LoginDto playerData, Player player) {
        var uuid = playerData.getUuid();
        var name = playerData.getName();
        var isLoggedIn = playerData.getLoginLink() == null;

        PlayerInfo target = Vars.netServer.admins.getInfoOptional(player.uuid());
        var isAdmin = playerData.getIsAdmin();

        if (uuid == null) {
            Log.warn("Player with null uuid: " + playerData);
            return;
        }

        Player playert = Groups.player.find(p -> p.getInfo() == target);

        if (target != null) {
            if (isAdmin) {
                Vars.netServer.admins.adminPlayer(target.id,
                        playert == null ? target.adminUsid : playert.usid());
            } else {
                Vars.netServer.admins.unAdminPlayer(target.id);
            }
            if (playert != null)
                playert.admin = isAdmin;
        }

        if (isLoggedIn) {
            player.sendMessage("Logged in as " + name);
        } else {
            player.sendMessage("You are not logged in, consider log in via MindustryTool using /login");
        }
    }
}
