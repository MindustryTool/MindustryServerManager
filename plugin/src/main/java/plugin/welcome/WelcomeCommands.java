package plugin.welcome;

import mindustry.gen.Call;
import plugin.Cfg;
import plugin.annotations.ClientCommand;
import plugin.annotations.Component;
import plugin.session.Session;

@Component
public class WelcomeCommands {

    @ClientCommand(name = "discord", description = "Open discord channel", admin = false)
    public void discord(Session session) {
        Call.openURI(session.player.con, Cfg.DISCORD_INVITE_URL);
    }

    @ClientCommand(name = "website", description = "Open mindustry tool official website", admin = false)
    public void website(Session session) {
        Call.openURI(session.player.con, Cfg.MINDUSTRY_TOOL_URL);
    }
}