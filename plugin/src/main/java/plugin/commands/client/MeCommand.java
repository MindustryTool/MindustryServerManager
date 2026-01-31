package plugin.commands.client;

import mindustry.gen.Player;
import plugin.commands.PluginCommand;
import plugin.handler.SessionHandler;

public class MeCommand extends PluginCommand {
    public MeCommand() {
        setName("me");
        setDescription("Display your info");
        setAdmin(false);
    }

    @Override
    public void handleClient(Player player) {
        SessionHandler.get(player).ifPresent(session -> player.sendMessage(session.info()));
    }
}
