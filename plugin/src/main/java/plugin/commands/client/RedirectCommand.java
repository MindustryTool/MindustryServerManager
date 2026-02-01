package plugin.commands.client;

import plugin.commands.PluginCommand;
import plugin.handler.ClientCommandHandler;
import plugin.type.Session;

public class RedirectCommand extends PluginCommand {
    public RedirectCommand() {
        setName("redirect");
        setDescription("Redirect all player to server");
    }

    @Override
    public void handleClient(Session session) {
        ClientCommandHandler.sendRedirectServerList(session, 0);
    }
}
