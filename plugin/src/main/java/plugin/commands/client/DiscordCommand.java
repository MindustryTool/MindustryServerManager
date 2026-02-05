package plugin.commands.client;

import mindustry.gen.Call;
import plugin.Component;
import plugin.Config;
import plugin.commands.PluginClientCommand;
import plugin.type.Session;

@Component
public class DiscordCommand extends PluginClientCommand {
    public DiscordCommand() {
        setName("discord");
        setDescription("Open discord channel");
        setAdmin(false);
    }

    @Override
    public void handle(Session session) {
        Call.openURI(session.player.con, Config.DISCORD_INVITE_URL);
    }
}
