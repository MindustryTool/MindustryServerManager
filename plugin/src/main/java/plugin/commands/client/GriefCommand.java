package plugin.commands.client;

import plugin.Component;
import plugin.Registry;
import plugin.commands.PluginCommand;
import plugin.handler.AdminHandler;
import plugin.menus.GriefMenu;
import plugin.type.Session;

@Component
public class GriefCommand extends PluginCommand {

    public GriefCommand() {
        setName("grief");
        setDescription("Report a player");
        setAdmin(false);
    }

    @Override
    public void handleClient(Session session) {
        var adminHandler = Registry.get(AdminHandler.class);
        if (adminHandler.isGriefVoting()) {
            adminHandler.voteGrief(session);
            return;
        }

        new GriefMenu().send(session, null);
    }
}
