package plugin.gamemode.flood;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import arc.Core;
import arc.Events;
import arc.struct.Seq;
import arc.util.Align;
import arc.util.Log;
import arc.util.Time;
import lombok.RequiredArgsConstructor;
import mindustry.Vars;
import mindustry.content.UnitTypes;
import mindustry.game.EventType;
import mindustry.game.Team;
import mindustry.game.EventType.BlockDestroyEvent;
import mindustry.gen.Building;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Iconc;
import mindustry.type.UnitType;
import plugin.annotations.Component;
import plugin.annotations.ConditionOn;
import plugin.gamemode.GamemodeCondition;
import plugin.annotations.Listener;
import plugin.annotations.MainThread;
import plugin.annotations.Schedule;
import plugin.annotations.Trigger;
import plugin.session.SessionCreatedEvent;
import plugin.utils.TimeUtils;
import plugin.utils.Tr;
import plugin.utils.Utils;

@ConditionOn(value = GamemodeCondition.class, args = { "flood" })
@Component
@RequiredArgsConstructor
public class FloodGamemode {

    private final FloodConfig config;

    private final ConcurrentHashMap<Building, Float> damageReceived = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Building, Long> suppressed = new ConcurrentHashMap<>();

    // Reused across ticks to avoid per-tick allocations.
    private final Seq<Building> unsuppressedCoresScratch = new Seq<>();

    private FloodSpreader spreader;

    private long startedAt = 0;
    private int cores = 1;

    private boolean isNight = false;
    private Instant cycleChangeAt = Instant.now();

    private Duration dayDuration = Duration.ofMinutes(12);
    private Duration nightDuration = Duration.ofMinutes(8);
    private int days = 0;

    private boolean shouldUpdate() {
        return Vars.state.isPlaying();
    }

    private void applyRules() {
        Vars.state.rules.enemyCoreBuildRadius = 0f;
        Team.crux.rules().extraCoreBuildRadius = 0f;

        spreader = new FloodSpreader(config);
        spreader.reset(Vars.world.width(), Vars.world.height());
        cores = Team.crux.cores().size;
        startedAt = Time.millis();
        cycleChangeAt = Instant.now();
        isNight = false;
        days = 0;

        suppressed.clear();
        damageReceived.clear();

        Log.info("Flood rules applied");
    }

    @Listener
    private void onPlayEvent(EventType.PlayEvent event) {
        applyRules();
    }

    @Listener
    private void onPlayerJoin(SessionCreatedEvent event) {
        event.session.player.sendMessage(Tr.t(event.session, "flood.objective"));
    }

    @Schedule(fixedRate = 30, unit = TimeUnit.SECONDS)
    private void spawnNightUnit() {
        if (!isNight || !shouldUpdate()) {
            return;
        }

        var unitCount = Groups.unit.count(u -> u.team == Team.crux);

        if (unitCount >= 50) {
            return;
        }

        UnitType unitType = null;

        if (days <= 0) {
            unitType = null;
        } else if (days < 2) {
            unitType = UnitTypes.atrax;
        } else if (days < 8) {
            unitType = UnitTypes.spiroct;
        } else if (days < 12) {
            unitType = UnitTypes.arkyid;
        } else {
            unitType = UnitTypes.toxopid;
        }

        if (unitType == null) {
            return;
        }
        for (int i = 0; i < suppressed.size(); i++) {
            var core = Team.crux.cores().random();
            if (core == null) {
                continue;
            }
            var unit = unitType.create(Team.crux);
            unit.set(core.getX(), core.getY());
            Core.app.post(() -> {
                unit.add();
            });
        }
    }

    @Schedule(fixedRate = 1, unit = TimeUnit.SECONDS)
    public void updateUI() {
        if (!shouldUpdate()) {
            return;
        }

        Duration time = Duration.between(Instant.now(), cycleChangeAt.plus(isNight ? nightDuration : dayDuration));

        Utils.forEachPlayerLocale((locale, players) -> {
            String label = Tr.t(locale, "flood.suppressed");
            for (var core : suppressed.keySet()) {
                for (var p : players) {
                    Call.label(p.con, "[scarlet]" + Iconc.warning + " " + label, 1.1f, core.getX(), core.getY());
                }
            }

            String color = isNight ? "[scarlet]" : "";
            String popup = Tr.t(locale, "flood.flood", "color", color, "percent", getFloodMultiplier() * 100) + "\n" +
                    Tr.t(locale, "flood.suppressed_count", "count", suppressed.size(), "total", cores) + "\n" +
                    Tr.t(locale, isNight ? "flood.night_in" : "flood.day_in", "color", color,
                            "time", TimeUtils.toSeconds(time))
                    + "\n" +
                    Tr.t(locale, "flood.days", "days", days);

            for (var p : players) {
                Call.infoPopup(p.con, popup, 1.1f, Align.top | Align.left, 200, 4, 4, 4);
            }
        });
    }

    @MainThread
    @Schedule(fixedRate = 1, unit = TimeUnit.SECONDS)
    private void update() {
        if (!shouldUpdate()) {
            return;
        }

        for (var core : Team.crux.cores()) {
            var damaged = core.maxHealth - core.health;
            core.maxHealth(100000000);
            core.heal();
            if (damaged > 0) {
                damageReceived.put(core, damageReceived.getOrDefault(core, 0f) + damaged);
            }
        }

        if (Instant.now().isAfter(cycleChangeAt.plus(isNight ? nightDuration : dayDuration))) {
            isNight = !isNight;
            cycleChangeAt = Instant.now();
            Vars.state.rules.lighting = isNight;
            Call.setRules(Vars.state.rules);
            if (!isNight) {
                days++;
            }
        }

    }

    @MainThread
    @Schedule(fixedDelay = 100, unit = TimeUnit.MILLISECONDS)
    private void updateUnitDamgeOnFlood() {
        if (!shouldUpdate()) {
            return;
        }

        for (var unit : Groups.unit) {
            if (unit.team == Team.crux) {
                continue;
            }

            var tile = unit.tileOn();

            if (tile == null || tile.build == null || tile.build.team != Team.crux) {
                continue;
            }
            var floodTile = config.floodTiles.find(t -> t.block == tile.build.block);

            if (floodTile == null) {
                continue;
            }

            unit.damage(floodTile.damage);
        }
    }

    @Trigger(EventType.Trigger.update)
    public void updateFlood() {
        if (!shouldUpdate()) {
            return;
        }

        if (spreader == null || !spreader.isInitialized()) {
            return;
        }

        suppressed.entrySet().removeIf(e -> e.getValue() < Time.millis() || !e.getKey().isValid());

        Seq<Building> unsuppressedCores = fillUnsuppressedCores();

        if (unsuppressedCores.isEmpty()) {
            return;
        }

        float multiplier = getFloodMultiplier();

        spreader.seed(unsuppressedCores, multiplier);
        spreader.tick(multiplier);
    }

    private Seq<Building> fillUnsuppressedCores() {
        Seq<Building> result = unsuppressedCoresScratch.clear();
        for (var core : Team.crux.cores()) {
            if (!suppressed.containsKey(core)) {
                result.add(core);
            }
        }
        return result;
    }

    @Listener
    private void onBlockDestroyed(BlockDestroyEvent event) {
        var tile = event.tile;
        var block = tile.build;

        if (spreader != null && block != null && block.team == Team.crux) {
            spreader.onTileDestroyed(spreader.posOf(tile));
        }
    }

    public float getFloodMultiplier() {
        // Well idk
        cores = Math.max(Math.max(cores, Team.crux.cores().size), 1);

        float elapsedMinutes = (Time.millis() - startedAt) / 1000 / 60;
        float destroyedCores = (cores - Team.crux.cores().size) + suppressed.size();

        return 1f + (destroyedCores / cores) + (0.01f * elapsedMinutes) + (isNight ? 1.5f : 0);
    }

    @MainThread
    @Schedule(fixedRate = 1, unit = TimeUnit.SECONDS)
    private void updateSuppress() {
        if (!shouldUpdate()) {
            return;
        }

        for (var entry : damageReceived.entrySet()) {
            var core = entry.getKey();
            var damage = entry.getValue();

            if (damage > config.suppressThreshold) {
                suppressed.put(core, Time.millis() + config.suppressTime);
            }
            damageReceived.put(core, 0f);
        }

        if (suppressed.size() == cores) {
            Events.fire(new FloodWonEvent());
            Events.fire(new EventType.GameOverEvent(Team.sharded));
        }
    }
}
