package plugin.shop;

import java.util.concurrent.TimeUnit;
import mindustry.game.EventType.PlayerJoin;
import mindustry.game.EventType.UnitSpawnEvent;
import mindustry.gen.Unit;
import plugin.Cfg;
import plugin.annotations.Component;
import plugin.annotations.ConditionOn;
import plugin.annotations.Listener;
import plugin.annotations.MainThread;
import plugin.annotations.Schedule;
import plugin.money.MoneyService;
import plugin.session.Session;
import plugin.session.SessionRepository;
import plugin.session.SessionService;
import plugin.utils.Tr;

@Component
@ConditionOn(Cfg.OnOfficial.class)
public class CoreUnitUpgradeService {
    public static final int MAX_TIER = 3;
    public static final long COST_TIER_1 = 800;
    public static final long COST_TIER_2 = 2000;
    public static final long COST_TIER_3 = 5000;

    private final MoneyService moneyService;
    private final SessionRepository sessionRepository;
    private final SessionService sessionService;

    public CoreUnitUpgradeService(MoneyService moneyService, SessionRepository sessionRepository, SessionService sessionService) {
        this.moneyService = moneyService;
        this.sessionRepository = sessionRepository;
        this.sessionService = sessionService;
    }

    public long getUpgradeCost(int nextTier) {
        return switch (nextTier) {
            case 1 -> COST_TIER_1;
            case 2 -> COST_TIER_2;
            case 3 -> COST_TIER_3;
            default -> -1;
        };
    }

    private void sendMessage(Session session, String message) {
        if (session.player != null) {
            session.player.sendMessage(message);
        }
    }

    public boolean upgrade(Session session) {
        synchronized (session.getData()) {
            int current = session.getData().coreUpgradeLevel;
            if (current >= MAX_TIER) {
                sendMessage(session, Tr.t(session, "shop.core_already_max"));
                return false;
            }

            int next = current + 1;
            long cost = getUpgradeCost(next);
            if (!moneyService.withdraw(session, cost)) {
                sendMessage(session, Tr.t(session, "shop.core_not_enough_coins", "cost", cost, "tier", next));
                return false;
            }

            session.getData().coreUpgradeLevel = next;
            sessionRepository.markDirty(session);
            applyContentPatch(session);

            sendMessage(session, Tr.t(session, "shop.core_success", "tier", next));
            return true;
        }
    }

    public void applyContentPatch(Session session) {
        if (session.player == null || session.player.unit() == null) {
            return;
        }
        int tier = session.getData().coreUpgradeLevel;
        if (tier <= 0) {
            return;
        }
        Unit unit = session.player.unit();
        float healthMultiplier = 1f + 0.3f * tier;
        float targetHealth = unit.type.health * healthMultiplier;
        if (unit.maxHealth < targetHealth) {
            unit.maxHealth(targetHealth);
            unit.health(Math.min(unit.health * healthMultiplier, targetHealth));
        }
    }

    @MainThread
    @Schedule(fixedDelay = 2, unit = TimeUnit.SECONDS)
    public void maintainUpgrades() {
        sessionService.each(this::applyContentPatch);
    }

    @Listener
    public void onPlayerJoin(PlayerJoin event) {
        sessionService.getByUuid(event.player.uuid()).ifPresent(this::applyContentPatch);
    }

    @Listener
    public void onUnitSpawn(UnitSpawnEvent event) {
        if (event.unit != null && event.unit.isPlayer() && event.unit.getPlayer() != null) {
            sessionService.getByUuid(event.unit.getPlayer().uuid()).ifPresent(this::applyContentPatch);
        }
    }
}
