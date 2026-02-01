package plugin.commands.client;

import mindustry.gen.Call;
import plugin.Config;
import plugin.commands.PluginCommand;
import plugin.type.Session;

public class WebsiteCommand extends PluginCommand {
    public WebsiteCommand() {
        setName("website");
        setDescription("Open mindustry tool official website");
        setAdmin(false);
    }

    @Override
    public void handleClient(Session session) {
        Call.openURI(session.player.con, Config.MINDUSTRY_TOOL_URL);
    }
}
