package plugin.commands.client;

import mindustry.gen.Player;
import plugin.commands.PluginCommand;
import plugin.handler.ClientCommandHandler;

public class RedirectCommand extends PluginCommand {
    public RedirectCommand() {
        setName("redirect");
        setDescription("Redirect all player to server");
    }

    @Override
    public void handleClient(Player player) {
        ClientCommandHandler.sendRedirectServerList(player, 0);
    }
}
