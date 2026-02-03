package plugin.commands.client;

import mindustry.gen.Call;
import plugin.Config;
import plugin.commands.PluginCommand;
import plugin.type.Session;

public class AdminCommand extends PluginCommand {
    public AdminCommand() {
        setName("admin");
        setDescription("Open discord channel");
        setAdmin(false);
    }

    @Override
    public void handleClient(Session session) {
        Call.openURI(session.player.con, Config.DISCORD_INVITE_URL);
    }
}
