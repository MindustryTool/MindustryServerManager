package plugin.commands.client;

import plugin.commands.PluginCommand;
import plugin.type.Session;
import plugin.utils.RankUtils;

public class RankCommand extends PluginCommand {
    public RankCommand() {
        setName("rank");
        setDescription("Show leader board");
        setAdmin(false);
    }

    @Override
    public void handleClient(Session session) {
        RankUtils.sendLeaderBoard();
    }
}
