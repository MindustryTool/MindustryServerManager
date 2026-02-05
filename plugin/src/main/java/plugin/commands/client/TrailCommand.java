package plugin.commands.client;

import plugin.annotations.Component;
import plugin.commands.PluginClientCommand;
import plugin.menus.TrailMenu;
import plugin.type.Session;

@Component
public class TrailCommand extends PluginClientCommand {
    public TrailCommand() {
        setName("trail");
        setDescription("Toggle trail");
        setAdmin(false);
    }

    @Override
    public void handle(Session session) {
        new TrailMenu().send(session, 0);
    }
}
