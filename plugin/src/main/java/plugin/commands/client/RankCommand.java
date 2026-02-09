package plugin.commands.client;

import plugin.annotations.Component;
import plugin.commands.PluginClientCommand;
import plugin.core.Registry;
import plugin.repository.SessionRepository;
import plugin.type.Session;
import plugin.utils.RankUtils;

@Component
public class RankCommand extends PluginClientCommand {
    public RankCommand() {
        setName("rank");
        setDescription("Show leader board");
        setAdmin(false);
    }

    @Override
    public void handle(Session session) {
        String data = RankUtils.getRankString(session.locale, Registry.get(SessionRepository.class).leaderBoard(10));
        session.player.sendMessage(data);
    }
}
