package plugin.commands.client;

import plugin.commands.PluginCommand;
import plugin.handler.TrailHandler;
import plugin.type.Session;

public class TrailCommand extends PluginCommand {
    public TrailCommand() {
        setName("trail");
        setDescription("Toggle trail");
        setAdmin(false);
    }

    @Override
    public void handleClient(Session session) {
        TrailHandler.toogle(session);
    }
}
