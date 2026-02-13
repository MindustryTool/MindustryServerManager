package plugin.commands.client;

import mindustry.gen.Call;
import plugin.Cfg;
import plugin.annotations.Component;
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
        Call.openURI(session.player.con, Cfg.DISCORD_INVITE_URL);
    }
}
