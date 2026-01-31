package plugin.commands.client;

import mindustry.gen.Call;
import mindustry.gen.Player;
import plugin.Config;
import plugin.commands.PluginCommand;

public class DiscordCommand extends PluginCommand {
    public DiscordCommand() {
        setName("discord");
        setDescription("Open discord channel");
        setAdmin(false);
    }

    @Override
    public void handleClient(Player player) {
        Call.openURI(player.con, Config.DISCORD_INVITE_URL);
    }
}
