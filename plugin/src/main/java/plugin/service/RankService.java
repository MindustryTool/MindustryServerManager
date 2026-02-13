package plugin.service;

import lombok.RequiredArgsConstructor;
import mindustry.gen.TimedKillc;
import plugin.Cfg;
import plugin.Tasks;
import plugin.annotations.Component;
import plugin.annotations.ConditionOn;
import plugin.annotations.Listener;
import plugin.event.PlayerKillUnitEvent;
import plugin.event.SessionCreatedEvent;
import plugin.repository.SessionRepository;
import plugin.utils.RankUtils;

@Component
@ConditionOn(Cfg.OnOfficial.class)
@RequiredArgsConstructor
public class RankService {
    private final SessionRepository sessionRepository;
    private final SessionHandler sessionHandler;
    private final SessionService sessionService;

    @Listener
    private void onSessionCreated(SessionCreatedEvent event) {
        Tasks.io("Send Leader Board",
                () -> event.session.player
                        .sendMessage(RankUtils.getRankString(event.session.locale, sessionRepository.leaderBoard(10))));

    }

    @Listener
    public void onPlayerKillUnit(PlayerKillUnitEvent event) {
        sessionHandler.get(event.getPlayer()).ifPresent(session -> {
            if (event.getUnit().type.isHidden() || event.getUnit().type instanceof TimedKillc) {
                return;
            }

            sessionService.addKill(session, event.getUnit().type, 1);
        });
    }
}
