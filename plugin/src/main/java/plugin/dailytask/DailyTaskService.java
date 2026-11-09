package plugin.dailytask;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import mindustry.Vars;
import mindustry.game.EventType.BlockBuildEndEvent;
import mindustry.game.EventType.UnitBulletDestroyEvent;
import mindustry.game.EventType.WaveEvent;
import mindustry.gen.Iconc;
import mindustry.gen.Player;
import mindustry.gen.Unit;
import plugin.Cfg;
import plugin.annotations.Component;
import plugin.annotations.ConditionOn;
import plugin.annotations.Listener;
import plugin.money.MoneyService;
import plugin.session.Session;
import plugin.session.SessionCreatedEvent;
import plugin.session.SessionData;
import plugin.session.SessionRepository;
import plugin.session.SessionService;
import plugin.utils.Tr;

@Component
@ConditionOn(Cfg.OnOfficial.class)
public class DailyTaskService {
    public static final long BUILD_DEBOUNCE_MILLIS = 500L;

    private final MoneyService moneyService;
    private final SessionRepository sessionRepository;
    private final SessionService sessionService;
    private final List<GamemodeTaskProvider> providers;
    private final Map<String, Long> lastBuildTime = new ConcurrentHashMap<>();

    public DailyTaskService(MoneyService moneyService, SessionRepository sessionRepository, SessionService sessionService) {
        this.moneyService = moneyService;
        this.sessionRepository = sessionRepository;
        this.sessionService = sessionService;
        this.providers = new ArrayList<>(List.of(
            new TowerDefenseTaskProvider(),
            new FloodTaskProvider(),
            new CataliTaskProvider(),
            new DefaultTaskProvider()
        ));
    }

    public List<GamemodeTaskProvider> getProviders() {
        return Collections.unmodifiableList(providers);
    }

    public List<DailyTask> getActiveTasks() {
        for (GamemodeTaskProvider provider : providers) {
            if (provider.supports()) {
                return provider.getTasks();
            }
        }
        return Collections.emptyList();
    }

    public void checkRollover(Session session) {
        String today = LocalDate.now().toString();
        SessionData data = session.getData();
        if (!today.equals(data.dailyTaskDate)) {
            data.dailyTaskDate = today;
            data.dailyTaskProgress.clear();
            sessionRepository.markDirty(session);
        }
    }

    public void resetTasks(Session session) {
        synchronized (session.getData()) {
            session.getData().dailyTaskDate = LocalDate.now().toString();
            session.getData().dailyTaskProgress.clear();
        }
        sessionRepository.markDirty(session);
    }

    public int getProgress(Session session, String taskId) {
        checkRollover(session);
        return session.getData().dailyTaskProgress.getOrDefault(taskId, 0);
    }

    public boolean isCompleted(Session session, DailyTask task) {
        return getProgress(session, task.getId()) >= task.getGoal();
    }

    public void addProgress(Session session, DailyTask.TaskType type, int amount) {
        if (amount <= 0) {
            return;
        }
        checkRollover(session);
        SessionData data = session.getData();

        for (DailyTask task : getActiveTasks()) {
            if (task.getType() == type) {
                int current = data.dailyTaskProgress.getOrDefault(task.getId(), 0);
                if (current >= task.getGoal()) {
                    continue;
                }
                int updated = current + amount;
                data.dailyTaskProgress.put(task.getId(), updated);
                sessionRepository.markDirty(session);

                if (updated >= task.getGoal() && current < task.getGoal()) {
                    moneyService.deposit(session, task.getRewardCoins());
                    if (session.player != null) {
                        session.player.sendMessage("[green]" + Iconc.ok + " " + Tr.t(session, "task.completed_notify",
                                "title", task.getTitle(session),
                                "coins", task.getRewardCoins()));
                    }
                }
            }
        }
    }

    @Listener
    public void onSessionCreated(SessionCreatedEvent event) {
        checkRollover(event.session);
    }

    @Listener
    public void onWave(WaveEvent event) {
        sessionService.each(session -> {
            if (!session.isAfk()) {
                addProgress(session, DailyTask.TaskType.SURVIVE_WAVES, 1);
            }
        });
    }

    @Listener
    public void onBlockBuildEnd(BlockBuildEndEvent event) {
        if (event.unit != null && event.unit.isPlayer() && !event.breaking) {
            Player player = event.unit.getPlayer();
            if (player != null) {
                long now = System.currentTimeMillis();
                long last = lastBuildTime.getOrDefault(player.uuid(), 0L);
                if (now - last < BUILD_DEBOUNCE_MILLIS) {
                    return;
                }
                lastBuildTime.put(player.uuid(), now);
                sessionService.get(player.uuid()).ifPresent(session -> {
                    addProgress(session, DailyTask.TaskType.BUILD_BLOCKS, 1);
                });
            }
        }
    }

    @Listener
    public void onUnitBulletDestroy(UnitBulletDestroyEvent event) {
        if (event.unit == null || event.bullet == null) {
            return;
        }
        if (event.unit.team == Vars.state.rules.defaultTeam) {
            return;
        }

        Player killer = null;
        Object owner = event.bullet.owner();
        if (owner instanceof Player p) {
            killer = p;
        } else if (owner instanceof Unit u && u.isPlayer()) {
            killer = u.getPlayer();
        }

        if (killer != null) {
            sessionService.get(killer.uuid()).ifPresent(session -> {
                addProgress(session, DailyTask.TaskType.KILL_UNITS, 1);
            });
        }
    }
}
