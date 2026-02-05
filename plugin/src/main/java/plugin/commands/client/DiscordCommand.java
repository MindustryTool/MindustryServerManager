package plugin.commands.client;

import mindustry.gen.Call;
import plugin.Component;
import plugin.Config;
import plugin.commands.PluginCommand;
import plugin.type.Session;

@Component
public class DiscordCommand extends PluginCommand {
    public DiscordCommand() {
        setName("discord");
        setDescription("Open discord channel");
        setAdmin(false);
    }

    @Override
    public void handleClient(Session session) {
        Call.openURI(session.player.con, Config.DISCORD_INVITE_URL);
    }
}
