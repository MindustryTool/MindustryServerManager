package plugin.commands.client;

import mindustry.gen.Player;
import plugin.commands.PluginCommand;
import plugin.menus.ServerListMenu;

public class ServersCommand extends PluginCommand {
    public ServersCommand() {
        setName("servers");
        setDescription("Display available servers");
        setAdmin(false);

    }

    @Override
    public void handleClient(Player player) {
        new ServerListMenu().send(player, 0);
    }
}
