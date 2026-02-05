package plugin.commands.client;

import plugin.Component;
import plugin.commands.PluginClientCommand;
import plugin.type.Session;

@Component
public class MeCommand extends PluginClientCommand {
    public MeCommand() {
        setName("me");
        setDescription("Display your info");
        setAdmin(false);
    }

    @Override
    public void handle(Session session) {
        session.player.sendMessage(session.info());
    }
}
