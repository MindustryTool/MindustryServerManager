package plugin.commands.client;

import plugin.Cfg;
import plugin.annotations.Component;
import plugin.annotations.ConditionOn;
import plugin.commands.PluginClientCommand;
import plugin.repository.SessionRepository;
import plugin.type.Session;
import plugin.utils.RankUtils;

@Component
@ConditionOn(Cfg.OnOfficial.class)
public class RankCommand extends PluginClientCommand {
    private final SessionRepository sessionRepository;

    public RankCommand(SessionRepository sessionRepository) {
        setName("rank");
        setDescription("Show leader board");
        setAdmin(false);

        this.sessionRepository = sessionRepository;
    }

    @Override
    public void handle(Session session) {
        String data = RankUtils.getRankString(session.locale, sessionRepository.leaderBoard(10));
        session.player.sendMessage(data);
    }
}
