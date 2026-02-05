package plugin.commands.client;

import plugin.Registry;
import plugin.annotations.Component;
import plugin.commands.PluginClientCommand;
import plugin.handler.AdminHandler;
import plugin.menus.GriefMenu;
import plugin.type.Session;

@Component
public class GriefCommand extends PluginClientCommand {

    public GriefCommand() {
        setName("grief");
        setDescription("Report a player");
        setAdmin(false);
    }

    @Override
    public void handle(Session session) {
        var adminHandler = Registry.get(AdminHandler.class);
        if (adminHandler.isGriefVoting()) {
            adminHandler.voteGrief(session);
            return;
        }

        new GriefMenu().send(session, null);
    }
}
