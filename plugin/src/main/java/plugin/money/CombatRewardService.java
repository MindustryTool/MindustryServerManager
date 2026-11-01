package plugin.money;

import mindustry.Vars;
import mindustry.game.EventType.UnitBulletDestroyEvent;
import mindustry.game.EventType.WaveEvent;
import mindustry.gen.Player;
import mindustry.gen.Unit;
import plugin.Cfg;
import plugin.annotations.Component;
import plugin.annotations.ConditionOn;
import plugin.annotations.Listener;
import plugin.session.SessionService;

@Component
@ConditionOn(Cfg.OnOfficial.class)
public class CombatRewardService {
    public static final long MAX_KILL_REWARD = 50L;

    private final MoneyService moneyService;
    private final SessionService sessionService;

    public CombatRewardService(MoneyService moneyService, SessionService sessionService) {
        this.moneyService = moneyService;
        this.sessionService = sessionService;
    }

    @Listener
    public void onWave(WaveEvent event) {
        long reward = Math.max(10, 10 + Vars.state.wave * 2L);
        sessionService.each(session -> {
            if (!session.isAfk()) {
                moneyService.deposit(session, reward);
            }
        });
    }

    @Listener
    public void onUnitBulletDestroy(UnitBulletDestroyEvent event) {
        if (event.unit == null || event.bullet == null) {
            return;
        }
        if (event.unit.isPlayer() || event.unit.getPlayer() != null) {
            return;
        }
        if (Vars.state != null && Vars.state.rules != null) {
            if (event.unit.team == Vars.state.rules.defaultTeam) {
                return;
            }
        }

        Player killer = null;
        Object owner = event.bullet.owner();
        if (owner instanceof Player p) {
            killer = p;
        } else if (owner instanceof Unit u && u.isPlayer()) {
            killer = u.getPlayer();
        }

        if (killer != null) {
            if (event.unit.team == killer.team()) {
                return;
            }
            sessionService.getByUuid(killer.uuid()).ifPresent(session -> {
                long rawReward = Math.max(1L, (long) (event.unit.maxHealth / 500f));
                long reward = Math.min(MAX_KILL_REWARD, rawReward);
                moneyService.deposit(session, reward);
            });
        }
    }
}
