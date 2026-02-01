package plugin.commands.client;

import plugin.commands.PluginCommand;
import plugin.menus.ServerListMenu;
import plugin.type.Session;

public class ServersCommand extends PluginCommand {
    public ServersCommand() {
        setName("servers");
        setDescription("Display available servers");
        setAdmin(false);

    }

    @Override
    public void handleClient(Session session) {
        new ServerListMenu().send(session, 0);
    }
}
