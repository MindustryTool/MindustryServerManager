package plugin.commands.client;

import plugin.commands.PluginCommand;
import plugin.handler.AdminHandler;
import plugin.menus.GriefMenu;
import plugin.type.Session;

public class GriefCommand extends PluginCommand {

    public GriefCommand() {
        setName("grief");
        setDescription("Report a player");
        setAdmin(false);
    }

    @Override
    public void handleClient(Session session) {
        if (AdminHandler.isGriefVoting()) {
            AdminHandler.voteGrief(session);
            return;
        }

        new GriefMenu().send(session, null);
    }
}
