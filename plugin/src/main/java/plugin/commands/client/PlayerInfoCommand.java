package plugin.commands.client;

import plugin.commands.PluginCommand;
import plugin.menus.PlayerInfoMenu;
import plugin.type.Session;

public class PlayerInfoCommand extends PluginCommand {
    public PlayerInfoCommand() {
        setName("playerinfo");
        setDescription("Display player info");
        setAdmin(false);
    }

    @Override
    public void handleClient(Session session) {
        new PlayerInfoMenu().send(session, session);
    }
}
