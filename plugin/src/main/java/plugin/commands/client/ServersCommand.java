package plugin.commands.client;

import mindustry.gen.Player;
import plugin.commands.PluginCommand;
import plugin.handler.EventHandler;

public class ServersCommand extends PluginCommand {
    public ServersCommand() {
        setName("servers");
        setDescription("Display available servers");
    }

    @Override
    public void handleClient(Player player) {
        EventHandler.sendServerList(player, 0);
    }
}
