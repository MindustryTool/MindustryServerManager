package plugin.commands.client;

import mindustry.gen.Player;
import plugin.commands.PluginCommand;
import plugin.menus.PlayerInfoMenu;

public class PlayerInfoCommand extends PluginCommand {
    public PlayerInfoCommand() {
        setName("playerinfo");
        setDescription("Display player info");
        setAdmin(false);
    }

    @Override
    public void handleClient(Player player) {
        new PlayerInfoMenu().send(player, player);
    }
}
