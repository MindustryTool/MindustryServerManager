package plugin.commands.client;

import plugin.Component;
import plugin.commands.PluginClientCommand;
import plugin.menus.PlayerInfoMenu;
import plugin.type.Session;

@Component
public class PlayerInfoCommand extends PluginClientCommand {
    public PlayerInfoCommand() {
        setName("pinfo");
        setDescription("Display player info");
        setAdmin(false);
    }

    @Override
    public void handle(Session session) {
        new PlayerInfoMenu().send(session, session);
    }
}
