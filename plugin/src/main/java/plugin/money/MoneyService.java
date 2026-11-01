package plugin.money;

import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import plugin.PluginEvents;
import plugin.annotations.Component;
import plugin.annotations.Schedule;
import plugin.session.Session;
import plugin.session.SessionRepository;
import plugin.session.SessionService;

@Component
@RequiredArgsConstructor
public class MoneyService {
    public static final long PLAYTIME_COINS_PER_INTERVAL = 10;
    public static final long MAX_BALANCE = 1_000_000_000_000L;

    private final SessionRepository sessionRepository;
    private final SessionService sessionService;

    public long getBalance(Session session) {
        return session.getData().money;
    }

    public void setBalance(Session session, long balance) {
        long clamped = Math.max(0L, Math.min(MAX_BALANCE, balance));
        synchronized (session.getData()) {
            session.getData().money = clamped;
        }
        sessionRepository.markDirty(session);
    }

    public void deposit(Session session, long amount) {
        if (amount <= 0) {
            return;
        }
        long actualGain;
        synchronized (session.getData()) {
            long current = session.getData().money;
            long target = Math.min(MAX_BALANCE, current + amount);
            actualGain = target - current;
            session.getData().money = target;
        }
        sessionRepository.markDirty(session);
        if (actualGain > 0) {
            PluginEvents.fire(new MoneyGainEvent(session, actualGain));
        }
    }

    public boolean withdraw(Session session, long amount) {
        if (amount <= 0) {
            return false;
        }
        synchronized (session.getData()) {
            if (session.getData().money < amount) {
                return false;
            }
            session.getData().money -= amount;
        }
        sessionRepository.markDirty(session);
        PluginEvents.fire(new MoneySpendEvent(session, amount));
        return true;
    }

    @Schedule(fixedDelay = 60, unit = TimeUnit.SECONDS)
    public void grantPlaytimeCoins() {
        sessionService.each(session -> {
            if (!session.isAfk()) {
                deposit(session, PLAYTIME_COINS_PER_INTERVAL);
            }
        });
    }
}
