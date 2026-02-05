package plugin.commands.client;

import plugin.Component;
import plugin.commands.PluginCommand;
import plugin.menus.GlobalServerListMenu;
import plugin.type.Session;

@Component
public class RedirectCommand extends PluginCommand {

    public RedirectCommand() {
        setName("redirect");
        setDescription("Redirect all player to server");
    }

    @Override
    public void handleClient(Session session) {
        new GlobalServerListMenu().send(session, 0);
    }
}
