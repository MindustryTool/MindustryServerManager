package plugin.commands.client;

import plugin.annotations.Component;
import plugin.commands.PluginClientCommand;
import plugin.type.Session;
import plugin.utils.SessionUtils;

@Component
public class MeCommand extends PluginClientCommand {
    public MeCommand() {
        setName("me");
        setDescription("Display your info");
        setAdmin(false);
    }

    @Override
    public void handle(Session session) {
        session.player.sendMessage(SessionUtils.getInfoString(session, session.getData()));
    }
}
