package plugin.shop;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import mindustry.content.UnitTypes;
import mindustry.game.EventType.WaveEvent;
import mindustry.gen.Unit;
import mindustry.type.UnitType;
import plugin.Cfg;
import plugin.annotations.Component;
import plugin.annotations.ConditionOn;
import plugin.annotations.Listener;
import plugin.money.MoneyService;
import plugin.session.Session;
import plugin.utils.Tr;

@Component
@ConditionOn(Cfg.OnOfficial.class)
public class PetService {
    public static final long COOLDOWN_MILLIS = 60_000;
    public static final int MAX_SUMMONS_PER_WAVE = 2;

    public static final long COST_POLY = 400;
    public static final long COST_MEGA = 1000;

    private final MoneyService moneyService;
    private final Map<String, Long> lastSummonTime = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> waveSummonCounts = new ConcurrentHashMap<>();

    public PetService(MoneyService moneyService) {
        this.moneyService = moneyService;
    }

    public long getPrice(UnitType type) {
        if (type == UnitTypes.mega) {
            return COST_MEGA;
        }
        return COST_POLY;
    }

    public long getRemainingCooldownSeconds(Session session) {
        String uuid = session.player.uuid();
        long lastTime = lastSummonTime.getOrDefault(uuid, 0L);
        long elapsed = System.currentTimeMillis() - lastTime;
        if (elapsed < COOLDOWN_MILLIS) {
            return (COOLDOWN_MILLIS - elapsed) / 1000 + 1;
        }
        return 0;
    }

    public int getWaveSummonCount(Session session) {
        String uuid = session.player.uuid();
        AtomicInteger count = waveSummonCounts.get(uuid);
        return count == null ? 0 : count.get();
    }

    private void sendMessage(Session session, String message) {
        if (session.player != null) {
            session.player.sendMessage(message);
        }
    }

    public boolean summonPet(Session session, UnitType type) {
        if (session.player == null) {
            return false;
        }

        long remaining = getRemainingCooldownSeconds(session);
        if (remaining > 0) {
            sendMessage(session, Tr.t(session, "pet.cooldown", "seconds", remaining));
            return false;
        }

        String uuid = session.player.uuid();
        AtomicInteger waveCount = waveSummonCounts.computeIfAbsent(uuid, k -> new AtomicInteger(0));
        if (waveCount.get() >= MAX_SUMMONS_PER_WAVE) {
            sendMessage(session, Tr.t(session, "pet.wave_limit", "limit", MAX_SUMMONS_PER_WAVE));
            return false;
        }

        long price = getPrice(type);
        if (!moneyService.withdraw(session, price)) {
            sendMessage(session, Tr.t(session, "pet.not_enough_coins", "price", price));
            return false;
        }

        Unit unit = type.create(session.player.team());
        if (unit != null) {
            unit.set(session.player.x, session.player.y);
            unit.add();
        }

        lastSummonTime.put(uuid, System.currentTimeMillis());
        waveCount.incrementAndGet();

        sendMessage(session, Tr.t(session, "pet.spawned", "name", type.name));
        return true;
    }

    @Listener
    public void onWave(WaveEvent event) {
        waveSummonCounts.clear();
    }
}
