package plugin.commands.client;

import mindustry.gen.Player;
import plugin.commands.PluginCommand;
import plugin.menus.HubMenu;

public class HubCommand extends PluginCommand {
    public HubCommand() {
        setName("hub");
        setDescription("Display available servers");
        setAdmin(false);
    }

    @Override
    public void handleClient(Player player) {
        new HubMenu().send(player, null);
    }
}
