package plugin.commands.client;

import mindustry.gen.Call;
import plugin.Cfg;
import plugin.annotations.Component;
import plugin.commands.PluginClientCommand;
import plugin.type.Session;

@Component
public class WebsiteCommand extends PluginClientCommand {
    public WebsiteCommand() {
        setName("website");
        setDescription("Open mindustry tool official website");
        setAdmin(false);
    }

    @Override
    public void handle(Session session) {
        Call.openURI(session.player.con, Cfg.MINDUSTRY_TOOL_URL);
    }
}
