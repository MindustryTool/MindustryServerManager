package plugin.session;

import java.time.LocalDate;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import plugin.Cfg.OnOfficial;
import plugin.PluginEvents;
import plugin.annotations.Component;
import plugin.annotations.ConditionOn;
import plugin.annotations.Listener;
import plugin.utils.Tr;

@Component
@ConditionOn(OnOfficial.class)
@RequiredArgsConstructor
public class DailyService {
    private static final long DAILY_BONUS_EXP = 3600;

    private final DailyRepository dailyRepository;

    @Listener
    public void onSessionCreated(SessionCreatedEvent event) {
        Session session = event.session;
        String uuid = session.player.uuid();
        String today = LocalDate.now().toString();

        Optional<String> lastLogin = dailyRepository.getLastLogin(uuid);

        if (lastLogin.isEmpty()) {
            dailyRepository.setLastLogin(uuid, today);
            return;
        }

        if (lastLogin.get().equals(today)) {
            return;
        }

        dailyRepository.setLastLogin(uuid, today);
        session.player.sendMessage(Tr.t(session, "session.daily_bonus", "bonus", "+" + DAILY_BONUS_EXP + " exp"));
        PluginEvents.fire(new ExpGainEvent(session, DAILY_BONUS_EXP));
    }
}
