package plugin.commands.client;

import plugin.Cfg;
import plugin.annotations.ClientCommand;
import plugin.annotations.Component;
import plugin.annotations.ConditionOn;
import plugin.repository.SessionRepository;
import plugin.type.Session;
import plugin.utils.RankUtils;

@Component
@ConditionOn(Cfg.OnOfficial.class)
public class OfficialCommands {
    private final SessionRepository sessionRepository;

    public OfficialCommands(SessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    @ClientCommand(name = "rank", description = "Show leader board", admin = false)
    public void rank(Session session) {
        String data = RankUtils.getRankString(session.locale, sessionRepository.leaderBoard(10));
        session.player.sendMessage(data);
    }
}
