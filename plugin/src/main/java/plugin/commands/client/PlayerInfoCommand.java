package plugin.commands.client;

import plugin.Component;
import plugin.commands.PluginCommand;
import plugin.menus.PlayerInfoMenu;
import plugin.type.Session;

@Component
public class PlayerInfoCommand extends PluginCommand {
    public PlayerInfoCommand() {
        setName("pinfo");
        setDescription("Display player info");
        setAdmin(false);
    }

    @Override
    public void handleClient(Session session) {
        new PlayerInfoMenu().send(session, session);
    }
}
