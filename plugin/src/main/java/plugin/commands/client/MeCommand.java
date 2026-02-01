package plugin.commands.client;

import plugin.commands.PluginCommand;
import plugin.type.Session;

public class MeCommand extends PluginCommand {
    public MeCommand() {
        setName("me");
        setDescription("Display your info");
        setAdmin(false);
    }

    @Override
    public void handleClient(Session session) {
        session.player.sendMessage(session.info());
    }
}
