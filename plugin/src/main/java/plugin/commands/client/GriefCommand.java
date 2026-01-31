package plugin.commands.client;

import mindustry.gen.Player;
import plugin.commands.PluginCommand;
import plugin.menus.GriefMenu;
import plugin.utils.AdminUtils;

public class GriefCommand extends PluginCommand {

    public GriefCommand() {
        setName("grief");
        setDescription("Report a player");
        setAdmin(false);
    }

    @Override
    public void handleClient(Player player) {
        if (AdminUtils.isGriefVoting()) {
            AdminUtils.voteGrief(player);
            return;
        }

        new GriefMenu().send(player, null);
    }
}
