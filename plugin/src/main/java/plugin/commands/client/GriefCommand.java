package plugin.commands.client;

import plugin.commands.PluginCommand;
import plugin.menus.GriefMenu;
import plugin.type.Session;
import plugin.utils.AdminUtils;

public class GriefCommand extends PluginCommand {

    public GriefCommand() {
        setName("grief");
        setDescription("Report a player");
        setAdmin(false);
    }

    @Override
    public void handleClient(Session session) {
        if (AdminUtils.isGriefVoting()) {
            AdminUtils.voteGrief(session);
            return;
        }

        new GriefMenu().send(session, null);
    }
}
