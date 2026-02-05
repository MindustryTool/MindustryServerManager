package plugin.commands.client;

import mindustry.gen.Call;
import plugin.Config;
import plugin.Component;
import plugin.commands.PluginClientCommand;
import plugin.type.Session;

@Component
public class AdminCommand extends PluginClientCommand {
    public AdminCommand() {
        setName("admin");
        setDescription("Open discord channel");
        setAdmin(false);
    }

    @Override
    public void handle(Session session) {
        Call.openURI(session.player.con, Config.DISCORD_INVITE_URL);
    }
}
