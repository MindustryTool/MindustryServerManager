package plugin.commands.client;

import mindustry.gen.Player;
import plugin.commands.PluginCommand;
import plugin.handler.EventHandler;

public class HubCommand extends PluginCommand {
    public HubCommand() {
        setName("hub");
        setDescription("Display available servers");
    }

    @Override
    public void handleClient(Player player) {
        EventHandler.sendHub(player, null);
    }
}
