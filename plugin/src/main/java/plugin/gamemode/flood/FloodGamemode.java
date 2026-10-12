package plugin.gamemode.flood;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import arc.Core;
import arc.Events;
import arc.math.Mathf;
import arc.struct.Seq;
import arc.util.Align;
import arc.util.Log;
import arc.util.Time;
import lombok.RequiredArgsConstructor;
import mindustry.Vars;
import mindustry.content.Fx;
import mindustry.content.UnitTypes;
import mindustry.entities.Damage;
import mindustry.entities.Effect;
import mindustry.game.EventType;
import mindustry.game.EventType.BlockBuildEndEvent;
import mindustry.game.EventType.BlockDestroyEvent;
import mindustry.game.EventType.UnitDestroyEvent;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Iconc;
import mindustry.gen.Sounds;
import mindustry.type.UnitType;
import mindustry.type.unit.MissileUnitType;
import mindustry.world.Tile;
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

    final ConcurrentHashMap<Building, Float> damageReceived = new ConcurrentHashMap<>();
    final ConcurrentHashMap<Building, Long> suppressed = new ConcurrentHashMap<>();
    final ConcurrentHashMap<Building, Long> suppressionDeadlines = new ConcurrentHashMap<>();

    // Reused across ticks to avoid per-tick allocations.
    private final Seq<Building> unsuppressedCoresScratch = new Seq<>();

    private FloodSpreader spreader;

    private long startedAt = 0;
    private int cores = 1;
    private boolean warnedNoCores = false;

    private boolean isNight = false;
    private Instant cycleChangeAt = Instant.now();

    private Duration dayDuration = Duration.ofMinutes(6);
    private Duration nightDuration = Duration.ofMinutes(4);
    private int days = 0;

    private boolean isGameOver = false; 

    boolean shouldUpdate() {
        return Vars.state != null && Vars.state.isPlaying();
    }

    private void applyRules() {
        Vars.state.rules.enemyCoreBuildRadius = 0f;
        Team.crux.rules().extraCoreBuildRadius = 0f;

        spreader = new FloodSpreader(config);
        spreader.reset(Vars.world.width(), Vars.world.height());
        spreader.scanExistingFlood(getFloodMultiplier());
        cores = Team.crux.cores().size;
        startedAt = Time.millis();
        cycleChangeAt = Instant.now();
        isNight = false;
        days = 0;

        suppressed.clear();
        suppressionDeadlines.clear();
        damageReceived.clear();
        warnedNoCores = false;
        isGameOver = false;

        Log.info("Flood rules applied: world=@x@ cores=@ tiers=@",
                Vars.world.width(), Vars.world.height(), Team.crux.cores().size,
                config.floodTiles.size);
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

        long unitCount = Groups.unit.count(u -> u.team == Team.crux);

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

        var multiplier = getFloodMultiplier();

        for (var core : Team.crux.cores()) {
            if (suppressed.containsKey(core)) {
                continue;
            }

            var unit = unitType.create(Team.crux);
            unit.health(unit.maxHealth * multiplier);
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
        long now = Time.millis();

        Utils.forEachPlayerLocale((locale, players) -> {
            String label = Tr.t(locale, "flood.suppressed");
            for (var entry : suppressed.entrySet()) {
                var core = entry.getKey();
                Long deadline = suppressionDeadlines.get(core);
                long remainingSec = deadline != null ? Math.max(0, (deadline - now + 999) / 1000) : 0;
                String labelText = "[scarlet]" + Iconc.warning + " " + label + " (" + remainingSec + "s)";
                for (var p : players) {
                    Call.label(p.con, labelText, 1.1f, core.getX(), core.getY());
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
            float damaged = core.maxHealth - core.health;
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
        if (!shouldUpdate() || spreader == null || !spreader.isInitialized()) {
            return;
        }

        for (var unit : Groups.unit) {
            if (unit.team == Team.crux) {
                continue;
            }

            var tile = unit.tileOn();
            if (tile == null) {
                continue;
            }

            var floodTile = spreader.getFloodTier(tile);
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

        if (Team.crux.cores().size == 0) {
            if (!warnedNoCores) {
                warnedNoCores = true;
                Log.err("Flood: map has no Team.crux cores - flood cannot spread, check the map spawn setup");
            }
            return;
        }

        long now = Time.millis();
        suppressed.entrySet().removeIf(e -> e.getValue() < now || !isCoreValid(e.getKey()));
        suppressionDeadlines.keySet().removeIf(core -> !suppressed.containsKey(core) || !isCoreValid(core));

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
        if (spreader == null || event.tile == null) {
            return;
        }

        var destroyedTier = spreader.getFloodTier(event.tile);
        trySpawnDestructionUnit(event.tile, destroyedTier);

        var lowerTier = spreader.getDowngradeTier(event.tile);
        if (lowerTier != null) {
            var tile = event.tile;
            var multiplier = getFloodMultiplier();
            spreader.onTileDestroyed(spreader.posOf(tile));
            Core.app.post(() -> {
                if (shouldUpdate() && spreader != null && spreader.isInitialized()) {
                    spreader.applyDowngradedTile(tile, lowerTier, multiplier);
                }
            });
            return;
        }

        spreader.onTileDestroyed(spreader.posOf(event.tile));
    }

    private void trySpawnDestructionUnit(Tile tile, FloodConfig.FloodTile tier) {
        if (!shouldUpdate() || tier == null || tier.unitType == null || tier.spawnChance <= 0f) {
            return;
        }

        long unitCount = Groups.unit.count(u -> u.team == Team.crux);
        if (unitCount >= 50) {
            return;
        }

        if (!Mathf.chance(tier.spawnChance)) {
            return;
        }

        var multiplier = getFloodMultiplier();
        var unit = tier.unitType.create(Team.crux);
        unit.health(unit.maxHealth * multiplier);
        unit.set(tile.worldx(), tile.worldy());

        Core.app.post(() -> {
            unit.add();
        });
    }

    @Listener
    private void onBlockBuildEnd(BlockBuildEndEvent event) {
        if (spreader != null && event.tile != null && event.breaking) {
            spreader.onTileDestroyed(spreader.posOf(event.tile));
        }
    }

    @Listener
    private void onUnitDestroy(UnitDestroyEvent event) {
        if (!shouldUpdate() || event.unit == null || event.unit.team != Team.crux) {
            return;
        }

        if (event.unit.type instanceof MissileUnitType) {
            return;
        }

        var tile = event.unit.tileOn();
        if (tile == null && Vars.world != null) {
            tile = Vars.world.tileWorld(event.unit.x, event.unit.y);
        }
        if (tile == null) {
            return;
        }

        if (spreader != null && spreader.isInitialized()) {
            spreader.tryPlaceLastTier(tile, getFloodMultiplier());
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
    void updateSuppress() {
        if (!shouldUpdate()) {
            return;
        }

        long now = Time.millis();
        suppressed.entrySet().removeIf(e -> e.getValue() < now || !isCoreValid(e.getKey()));
        suppressionDeadlines.keySet().removeIf(core -> !suppressed.containsKey(core) || !isCoreValid(core));

        for (var entry : damageReceived.entrySet()) {
            var core = entry.getKey();
            var damage = entry.getValue();

            if (damage > config.suppressThreshold) {
                suppressed.put(core, now + config.suppressTime);
                if (!suppressionDeadlines.containsKey(core)) {
                    suppressionDeadlines.put(core, now + config.coreExplosionTime);
                }
            }
            damageReceived.put(core, 0f);
        }

        for (var entry : suppressionDeadlines.entrySet()) {
            var core = entry.getKey();
            long deadline = entry.getValue();
            if (now >= deadline && isCoreValid(core)) {
                explodeCore(core);
            }
        }

        int aliveCores = Team.crux.cores().size;
        if (!isGameOver && (aliveCores == 0 || (aliveCores > 0 && suppressed.size() == aliveCores))) {
            Events.fire(new FloodWonEvent());
            Events.fire(new EventType.GameOverEvent(Team.sharded));
            isGameOver = true;
        }
    }

    private boolean isCoreValid(Building core) {
        return core != null && core.tile != null && core.isValid();
    }

    private void explodeCore(Building core) {
        Damage.damage(core.x, core.y, 19f * Vars.tilesize, 5000f);
        Fx.reactorExplosion.at(core);
        Sounds.explosionReactor.at(core);
        Effect.shake(6f, 16f, core);

        suppressed.remove(core);
        suppressionDeadlines.remove(core);
        damageReceived.remove(core);

        core.kill();
    }
}
