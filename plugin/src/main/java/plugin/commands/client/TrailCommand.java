package plugin.commands.client;

import plugin.commands.PluginCommand;
import plugin.menus.TrailMenu;
import plugin.type.Session;

public class TrailCommand extends PluginCommand {
    public TrailCommand() {
        setName("trail");
        setDescription("Toggle trail");
        setAdmin(false);
    }

    @Override
    public void handleClient(Session session) {
        new TrailMenu().send(session, 0);
    }
}
