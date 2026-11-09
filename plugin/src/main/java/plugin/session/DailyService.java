package plugin.session;

import java.time.LocalDate;
import java.util.Optional;

import arc.Core;
import lombok.RequiredArgsConstructor;
import plugin.Cfg.OnOfficial;
import plugin.PluginEvents;
import plugin.Tasks;
import plugin.annotations.Component;
import plugin.annotations.ConditionOn;
import plugin.annotations.Listener;
import plugin.money.MoneyService;
import plugin.utils.Tr;

@Component
@ConditionOn(OnOfficial.class)
@RequiredArgsConstructor
public class DailyService {
    private static final long DAILY_BONUS_EXP = 3600;
    private static final long DAILY_BONUS_COINS = 500;

    private final DailyRepository dailyRepository;
    private final MoneyService moneyService;

    @Listener
    public void onSessionCreated(SessionCreatedEvent event) {
        Session session = event.session;
        String uuid = session.player.uuid();
        String today = LocalDate.now().toString();

        Tasks.io("daily bonus " + uuid, () -> {
            Optional<String> lastLogin = dailyRepository.getLastLogin(uuid);

            if (lastLogin.isEmpty()) {
                dailyRepository.setLastLogin(uuid, today);
                return;
            }

            if (lastLogin.get().equals(today)) {
                return;
            }

            dailyRepository.setLastLogin(uuid, today);
            Core.app.post(() -> grantBonus(session));
        });
    }

    private void grantBonus(Session session) {
        session.player.sendMessage(Tr.t(session, "session.daily_bonus", "bonus", "+" + DAILY_BONUS_EXP + " exp, +" + DAILY_BONUS_COINS + " coins"));
        PluginEvents.fire(new ExpGainEvent(session, DAILY_BONUS_EXP));
        moneyService.deposit(session, DAILY_BONUS_COINS);
    }
}
