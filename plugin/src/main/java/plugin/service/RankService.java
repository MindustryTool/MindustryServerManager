package plugin.service;

import lombok.RequiredArgsConstructor;
import mindustry.gen.TimedKillc;
import plugin.Cfg;
import plugin.annotations.Component;
import plugin.annotations.ConditionOn;
import plugin.annotations.Listener;
import plugin.event.PlayerKillUnitEvent;

@Component
@ConditionOn(Cfg.OnOfficial.class)
@RequiredArgsConstructor
public class RankService {
    private final SessionHandler sessionHandler;
    private final SessionService sessionService;

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
