package plugin.commands.client;

import plugin.type.Session;
import plugin.commands.PluginCommand;
import plugin.menus.HubMenu;

public class HubCommand extends PluginCommand {
    public HubCommand() {
        setName("hub");
        setDescription("Display available servers");
        setAdmin(false);
    }

    @Override
    public void handleClient(Session session) {
        new HubMenu().send(session, null);
    }
}
