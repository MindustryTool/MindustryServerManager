package plugin.gamemode.flood;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.Iterator;
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
import mindustry.content.Blocks;
import mindustry.content.UnitTypes;
import mindustry.game.EventType;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Iconc;
import mindustry.type.UnitType;
import mindustry.world.Tile;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;
import plugin.annotations.Gamemode;
import plugin.annotations.Init;
import plugin.annotations.Listener;
import plugin.annotations.Schedule;
import plugin.annotations.Trigger;
import plugin.gamemode.flood.FloodConfig.FloodTile;
import plugin.utils.TimeUtils;

@Gamemode("flood")
@RequiredArgsConstructor
public class FloodGamemode {

    private final FloodConfig config;

    private final ConcurrentHashMap<Building, Float> damageReceived = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Building, Long> suppressed = new ConcurrentHashMap<>();

    private long startedAt = 0;
    private long[] floods = new long[0];
    BitSet spreaded = new BitSet(floods.length);
    private int cores = 1;

    private Iterator<CoreBuild> coreIterator;
    private boolean processing = false;
    private boolean isNight = false;
    private Instant cycleChangeAt = Instant.now();

    private Duration dayDuration = Duration.ofMinutes(6);
    private Duration nightDuration = Duration.ofMinutes(4);

    @Init
    private void applyRules() {
        Vars.state.rules.enemyCoreBuildRadius = 0f;
        Team.crux.rules().extraCoreBuildRadius = 0f;

        floods = new long[Vars.world.width() * Vars.world.height()];
        spreaded = new BitSet(floods.length);
        cores = Team.crux.cores().size;
        startedAt = Time.millis();

        suppressed.clear();
        damageReceived.clear();

        Log.info("Flood rules applied");
    }

    @Listener
    private void onPlayEvent(EventType.PlayEvent event) {
        applyRules();
    }

    @Schedule(fixedRate = 10, unit = TimeUnit.SECONDS)
    private void spawnNightUnit() {
        if (!isNight) {
            return;
        }

        var unitCount = Groups.unit.count(u -> u.team == Team.crux);

        if (unitCount >= 100) {
            return;
        }

        var elapsedMinutes = (Time.millis() - startedAt) / 1_000L / 60L;
        UnitType unitType = null;

        if (elapsedMinutes < 5) {
            unitType = null;
        } else if (elapsedMinutes < 10) {
            unitType = UnitTypes.atrax;
        } else if (elapsedMinutes < 30) {
            unitType = UnitTypes.spiroct;
        } else if (elapsedMinutes < 60) {
            unitType = UnitTypes.arkyid;
        } else if (elapsedMinutes < 90) {
            unitType = UnitTypes.toxopid;
        }

        if (unitType == null) {
            return;
        }

        for (var core : Team.crux.cores()) {
            var unit = unitType.create(Team.crux);
            Core.app.post(() -> {
                unit.set(core.getX(), core.getY());
                unit.add();
            });
        }
    }

    @Schedule(fixedRate = 1, unit = TimeUnit.SECONDS)
    public void updateUI() {
        for (var core : suppressed.keySet()) {
            Call.label("[scarlet]" + Iconc.warning + " *Suppressed*", 1.1f, core.getX(), core.getY());
        }

        Duration time = Duration.between(cycleChangeAt.plus(isNight ? nightDuration : dayDuration), Instant.now());

        Call.infoPopup((isNight ? "[scarlet]" : "") + "Flood: " + getFloodMultiplier() * 100 + "%\n" +
                "Suppressed: " + suppressed.size() + "/" + cores + "\n" +
                (isNight ? "Day in" : "Night in") + ": " + TimeUtils.toSeconds(time)
        //
                , 1.1f, Align.center | Align.right, 4, 4, 4, 4);
    }

    @Trigger(EventType.Trigger.update)
    private void update() {
        for (var core : Team.crux.cores()) {
            var damaged = core.maxHealth - core.health;
            core.maxHealth(100000000);
            core.heal();
            if (damaged > 0) {
                damageReceived.put(core, damageReceived.getOrDefault(core, 0f) + damaged);
            }
        }

        if (Duration.between(cycleChangeAt, Instant.now()).compareTo(isNight ? nightDuration : dayDuration) >= 0) {
            isNight = !isNight;
            cycleChangeAt = Instant.now();
            Vars.state.rules.lighting = !isNight;
            Call.setRules(Vars.state.rules);
        }

    }

    @Schedule(fixedDelay = 1, unit = TimeUnit.SECONDS)
    private void updateUnitDamgeOnFlood() {
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

            Core.app.post(() -> unit.damage(floodTile.damage));
        }
    }

    @Schedule(fixedDelay = 34, unit = TimeUnit.MILLISECONDS)
    public void updateFlood() {
        if (floods.length == 0) {
            return;
        }

        suppressed.entrySet().removeIf(e -> e.getValue() < Time.millis());

        if (!processing) {
            coreIterator = Team.crux.cores().iterator();
            processing = true;
            spreaded.clear();
        }

        if (coreIterator == null || !coreIterator.hasNext()) {
            processing = false;
            return;
        }

        Building core = coreIterator.next();

        while (suppressed.containsKey(core) && coreIterator.hasNext()) {
            core = coreIterator.next();
        }

        var tiles = around(core);
        var multiplier = getFloodMultiplier();

        for (var tile : tiles) {
            if (tile.build != null && tile.build.team != Team.crux) {
                Core.app.post(() -> tile.build.damage(config.floodTiles.get(0).damage * multiplier));
            }
        }

        if (tiles.isEmpty()) {
            return;
        }

        for (var tile : tiles) {
            if (tile.build == null) {
                setFlood(tile, config.floodTiles.get(0), multiplier);
            } else {
                spread(tile, spreaded, multiplier);
            }
        }
    }

    private void spread(Tile start, BitSet spreaded, float multiplier) {
        int MAX_UPDATES = 150;
        int updates = 0;

        ArrayDeque<Tile> queue = new ArrayDeque<>(MAX_UPDATES);

        spreaded.set(index(start), true);
        queue.add(start);

        var currentTime = Time.millis();

        while (!queue.isEmpty() && updates < MAX_UPDATES) {
            Tile tile = queue.poll();

            var build = tile.build;

            // Not flood skip
            if (build == null || build.team != Team.crux) {
                continue;
            }

            var evolveAt = floods[index(tile)];

            if (evolveAt > 0 && evolveAt < currentTime) {
                var next = config.nextTier(build);
                if (next != null) {
                    setFlood(tile, next, multiplier);
                    updates++;
                }
            }

            for (Tile neighbor : around(tile.build)) {
                var neighborBuild = neighbor.build;

                if (neighborBuild == null) {
                    if (neighbor.block() == Blocks.air || neighbor.block().alwaysReplace) {
                        var time = floods[index(neighbor)];
                        if (time <= 0) {
                            floods[index(neighbor)] = currentTime + Mathf.random(1000 * 1, 1000 * 5);
                        } else if (time <= currentTime) {
                            setFlood(neighbor, config.floodTiles.get(0), multiplier);
                            updates++;
                        }
                    }
                } else {
                    if (neighborBuild.team != Team.crux) {
                        var currentTier = config.floodTiles.find(f -> f.block == build.block);
                        if (currentTier != null) {
                            Core.app.post(() -> neighborBuild.damage(currentTier.damage * multiplier));
                        }
                    } else if (!spreaded.get(index(neighbor))) {
                        spreaded.set(index(neighbor), true);
                        queue.add(neighbor);
                    }
                }
            }
        }
    }

    private void setFlood(Tile tile, FloodTile floodTile, float multiplier) {
        Core.app.post(() -> tile.setNet(floodTile.block, Team.crux, 0));

        floods[index(tile)] = Time.millis() + (long) (floodTile.evolveTime * 1000 / multiplier)
                + Mathf.random(0, 1000 * 1);
    }

    private int index(Tile tile) {
        return tile.x + tile.y * Vars.world.width();
    }

    private Seq<Tile> around(Building core) {
        Seq<Tile> tiles = new Seq<>();

        int half = core.block.size / 2;
        int cx = core.tile.x;
        int cy = core.tile.y;

        for (int y = cy - half; y <= cy + half; y++) {
            Tile left = Vars.world.tile(cx - half - 1, y);
            Tile right = Vars.world.tile(cx + half + 1, y);

            if (left != null)
                tiles.add(left);
            if (right != null)
                tiles.add(right);
        }

        for (int x = cx - half; x <= cx + half; x++) {
            Tile bottom = Vars.world.tile(x, cy - half - 1);
            Tile top = Vars.world.tile(x, cy + half + 1);

            if (bottom != null)
                tiles.add(bottom);
            if (top != null)
                tiles.add(top);
        }

        return tiles;
    }

    public float getFloodMultiplier() {
        // Well idk
        cores = Math.max(Math.max(cores, Team.crux.cores().size), 1);

        var elapsedMinutes = (Time.millis() - startedAt) / 1000 / 60;
        var destroyedCores = cores - Team.crux.cores().size;

        return 1f + (destroyedCores / cores) + (0.01f * elapsedMinutes) + (isNight ? 2 : 0);
    }

    @Schedule(fixedRate = 1, unit = TimeUnit.SECONDS)
    private void updateSuppress() {
        for (var entry : damageReceived.entrySet()) {
            var core = entry.getKey();
            var damage = entry.getValue();

            if (damage > config.suppressThreshold) {
                suppressed.put(core, Time.millis() + config.suppressTime);
            }
            damageReceived.put(core, 0f);
        }

        if (suppressed.size() == cores) {
            Events.fire(new EventType.GameOverEvent(Team.sharded));
        }
    }
}
