package plugin.commands.client;

import mindustry.gen.Call;
import mindustry.gen.Player;
import plugin.Config;
import plugin.commands.PluginCommand;

public class WebsiteCommand extends PluginCommand {
    public WebsiteCommand() {
        setName("website");
        setDescription("Open mindustry tool official website");
        setAdmin(false);
    }

    @Override
    public void handleClient(Player player) {
        Call.openURI(player.con, Config.MINDUSTRY_TOOL_URL);
    }
}
