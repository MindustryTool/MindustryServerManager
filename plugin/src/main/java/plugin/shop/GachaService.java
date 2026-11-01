package plugin.shop;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import mindustry.Vars;
import mindustry.gen.Iconc;
import plugin.annotations.Component;
import plugin.money.MoneyService;
import plugin.session.Session;
import plugin.session.SessionRepository;
import plugin.utils.Tr;

@Component
public class GachaService {
    public static final long COST_SINGLE = 200;
    public static final long COST_MULTI_10 = 1800;
    public static final long ROLL_COOLDOWN_MILLIS = 400L;

    private static final String[] GACHA_TRAILS = { "shock", "lightning", "fire", "heal" };

    private final MoneyService moneyService;
    private final SessionRepository sessionRepository;
    private final LootTable<GachaReward> lootTable;
    private final Random random;
    private final Map<String, Long> lastRollTime = new ConcurrentHashMap<>();

    public GachaService(MoneyService moneyService, SessionRepository sessionRepository) {
        this(moneyService, sessionRepository, new Random());
    }

    public GachaService(MoneyService moneyService, SessionRepository sessionRepository, Random random) {
        this.moneyService = moneyService;
        this.sessionRepository = sessionRepository;
        this.random = random;
        this.lootTable = createDefaultLootTable();
    }

    private LootTable<GachaReward> createDefaultLootTable() {
        LootTable<GachaReward> table = new LootTable<>();
        table.add(5.0, session -> {
            moneyService.deposit(session, 1500);
            return Tr.t(session, "gacha.jackpot");
        });
        table.add(15.0, session -> {
            String trail = GACHA_TRAILS[random.nextInt(GACHA_TRAILS.length)];
            if (session.getData().unlockedTrails == null) {
                session.getData().unlockedTrails = new ArrayList<>();
            }
            if (!session.getData().unlockedTrails.contains(trail)) {
                session.getData().unlockedTrails.add(trail);
                sessionRepository.markDirty(session);
                return Tr.t(session, "gacha.trail_unlocked", "trail", trail);
            } else {
                moneyService.deposit(session, 500);
                return Tr.t(session, "gacha.duplicate_trail", "trail", trail);
            }
        });
        table.add(25.0, session -> {
            moneyService.deposit(session, 300);
            return Tr.t(session, "gacha.coin_prize", "color", "[yellow]", "coins", 300);
        });
        table.add(35.0, session -> {
            moneyService.deposit(session, 100);
            return Tr.t(session, "gacha.coin_prize", "color", "[lightgray]", "coins", 100);
        });
        table.add(20.0, session -> {
            moneyService.deposit(session, 50);
            return Tr.t(session, "gacha.coin_prize", "color", "[gray]", "coins", 50);
        });
        return table;
    }

    public LootTable<GachaReward> getLootTable() {
        return lootTable;
    }

    private void sendMessage(Session session, String message) {
        if (session.player != null && Vars.net != null) {
            session.player.sendMessage(message);
        }
    }

    public boolean canRoll(Session session) {
        if (session == null || session.player == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        long last = lastRollTime.getOrDefault(session.player.uuid(), 0L);
        if (now - last < ROLL_COOLDOWN_MILLIS) {
            return false;
        }
        lastRollTime.put(session.player.uuid(), now);
        return true;
    }

    public String rollSingle(Session session) {
        if (!canRoll(session)) {
            return null;
        }
        synchronized (session.getData()) {
            if (!moneyService.withdraw(session, COST_SINGLE)) {
                sendMessage(session, Tr.t(session, "gacha.not_enough_coins", "cost", COST_SINGLE));
                return null;
            }

            String result = executeRoll(session);
            sendMessage(session, "[gold]" + Iconc.star + " " + Tr.t(session, "gacha.result_single", "result", result));
            return result;
        }
    }

    public List<String> rollTen(Session session) {
        if (!canRoll(session)) {
            return List.of();
        }
        synchronized (session.getData()) {
            if (!moneyService.withdraw(session, COST_MULTI_10)) {
                sendMessage(session, Tr.t(session, "gacha.not_enough_coins", "cost", COST_MULTI_10));
                return List.of();
            }

            List<String> results = new ArrayList<>();
            sendMessage(session, "[gold]" + Iconc.star + " " + Tr.t(session, "gacha.result_ten_header"));
            for (int i = 0; i < 10; i++) {
                String result = executeRoll(session);
                results.add(result);
                sendMessage(session, Tr.t(session, "gacha.result_entry", "result", result));
            }
            return results;
        }
    }

    public String executeRoll(Session session) {
        GachaReward reward = lootTable.roll(random);
        return reward.apply(session);
    }
}
