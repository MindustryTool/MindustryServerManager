package plugin.commands.client;

import plugin.Component;
import plugin.commands.PluginClientCommand;
import plugin.menus.ServerListMenu;
import plugin.type.Session;

@Component
public class ServersCommand extends PluginClientCommand {

    public ServersCommand() {
        setName("servers");
        setDescription("Display available servers");
        setAdmin(false);

    }

    @Override
    public void handle(Session session) {
        new ServerListMenu().send(session, 0);
    }
}
