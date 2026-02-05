package plugin.commands.client;

import plugin.annotations.Component;
import plugin.commands.PluginClientCommand;
import plugin.menus.GlobalServerListMenu;
import plugin.type.Session;

@Component
public class RedirectCommand extends PluginClientCommand {

    public RedirectCommand() {
        setName("redirect");
        setDescription("Redirect all player to server");
    }

    @Override
    public void handle(Session session) {
        new GlobalServerListMenu().send(session, 0);
    }
}
