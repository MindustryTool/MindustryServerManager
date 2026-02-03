package plugin.commands.client;

import plugin.commands.PluginCommand;
import plugin.repository.SessionRepository;
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
        String data = RankUtils.getRankString(SessionRepository.getLeaderBoard(10));
        session.player.sendMessage(data);
    }
}
