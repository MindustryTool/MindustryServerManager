package plugin.session;

import java.time.LocalDate;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import plugin.PluginEvents;
import plugin.annotations.Component;
import plugin.annotations.Listener;
import plugin.utils.I18n;

@Component
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
        session.player.sendMessage(I18n.t(session, "@Daily login bonus", "+" + DAILY_BONUS_EXP + " exp"));
        PluginEvents.fire(new ExpGainEvent(session, DAILY_BONUS_EXP));
    }
}