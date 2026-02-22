package plugin.gamemode.flood;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

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
import mindustry.game.EventType.BlockDestroyEvent;
import mindustry.gen.Building;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Iconc;
import mindustry.type.UnitType;
import mindustry.world.Block;
import mindustry.world.Tile;
import plugin.annotations.Gamemode;
import plugin.annotations.Listener;
import plugin.annotations.MainThread;
import plugin.annotations.Schedule;
import plugin.annotations.Trigger;
import plugin.gamemode.flood.FloodConfig.FloodTile;
import plugin.utils.TimeUtils;
import plugin.utils.Utils;

@Gamemode("flood")
@RequiredArgsConstructor
public class FloodGamemode {

    private final FloodConfig config;

    private final ConcurrentHashMap<Building, Float> damageReceived = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Building, Long> suppressed = new ConcurrentHashMap<>();
    private final ArrayDeque<Tile> queue = new ArrayDeque<>();

    private long startedAt = 0;
    private long[] floods = new long[0];
    private BitSet spreaded = new BitSet(floods.length);
    private HashMap<Block, Seq<Integer>> updatedTiles = new HashMap<>();
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

        floods = new long[Vars.world.width() * Vars.world.height()];
        spreaded = new BitSet(floods.length);
        cores = Team.crux.cores().size;
        startedAt = Time.millis();
        cycleChangeAt = Instant.now();
        isNight = false;
        days = 0;

        suppressed.clear();
        damageReceived.clear();
        queue.clear();

        Log.info("Flood rules applied");
    }

    @Listener
    private void onPlayEvent(EventType.PlayEvent event) {
        applyRules();
    }

    @Schedule(fixedRate = 30, unit = TimeUnit.SECONDS)
    private void spawnNightUnit() {
        if (!isNight || !shouldUpdate()) {
            return;
        }

        var unitCount = Groups.unit.count(u -> u.team == Team.crux);

        if (unitCount >= 100) {
            return;
        }

        UnitType unitType = null;

        if (days <= 0) {
            unitType = null;
        } else if (days < 2) {
            unitType = UnitTypes.atrax;
        } else if (days < 4) {
            unitType = UnitTypes.spiroct;
        } else if (days < 5) {
            unitType = UnitTypes.arkyid;
        } else {
            unitType = UnitTypes.toxopid;
        }

        if (unitType == null) {
            return;
        }
        for (int i = 0; i < suppressed.size(); i++) {
            var core = Team.crux.cores().random();
            var unit = unitType.create(Team.crux);
            unit.set(core.getX(), core.getY());
            Utils.appPostWithTimeout(() -> {
                unit.add();
            }, "Spawn night unit");
        }
    }

    @Schedule(fixedRate = 1, unit = TimeUnit.SECONDS)
    public void updateUI() {
        if (!shouldUpdate()) {
            return;
        }

        for (var core : suppressed.keySet()) {
            Call.label("[scarlet]" + Iconc.warning + " *Suppressed*", 1.1f, core.getX(), core.getY());
        }

        Duration time = Duration.between(Instant.now(), cycleChangeAt.plus(isNight ? nightDuration : dayDuration));

        Call.infoPopup((isNight ? "[scarlet]" : "") + "Flood: " + getFloodMultiplier() * 100 + "%\n" +
                "Suppressed: " + suppressed.size() + "/" + cores + "\n" +
                (isNight ? "Day in" : "Night in") + ": " + TimeUtils.toSeconds(time) + "\n" +
                "Days: " + days
        //
                , 1.1f, Align.top | Align.left, 200, 4, 4, 4);
    }

    @Trigger(EventType.Trigger.update)
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
    @Schedule(fixedDelay = 1, unit = TimeUnit.SECONDS)
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

        if (floods.length != Vars.world.width() * Vars.world.height()) {
            return;
        }

        suppressed.entrySet().removeIf(e -> e.getValue() < Time.millis() || !e.getKey().isValid());

        var cores = Team.crux.cores();
        var unsuppressedCores = cores.select(c -> !suppressed.containsKey(c));

        if (unsuppressedCores.isEmpty()) {
            return;
        }

        int totalUpdates = 10;
        int updatesPerCore = Math.max(totalUpdates / Math.max(1, unsuppressedCores.size), 1);
        float multiplier = getFloodMultiplier();

        if (queue.isEmpty()) {
            spreaded.clear();

            for (var core : unsuppressedCores) {
                var tiles = around(core);

                for (var tile : tiles) {
                    if (tile.build != null && tile.build.team != Team.crux) {
                        final var damage = config.floodTiles.get(0).damage * multiplier;
                        tile.build.damage(damage);
                    }

                    if (tile.build == null) {
                        setFlood(tile, config.floodTiles.get(0), multiplier);
                    } else {
                        if (!spreaded.get(index(tile))) {
                            spreaded.set(index(tile), true);
                        }
                    }

                    queue.add(tile);
                }
            }
        }

        spread(queue, spreaded, multiplier, updatesPerCore);

        for (var entry : updatedTiles.entrySet()) {
            var block = entry.getKey();
            var tiles = entry.getValue();

            int[] primitive = new int[tiles.size];

            for (int i = 0; i < tiles.size; i++) {
                primitive[i] = tiles.get(i);
            }

            Call.setTileBlocks(block, Team.crux, primitive);
        }

        updatedTiles.clear();
    }

    private void spread(ArrayDeque<Tile> queue, BitSet spreaded, float multiplier, int maxUpdates) {
        int updates = 0;

        var currentTime = Time.millis();

        while (!queue.isEmpty() && updates < maxUpdates) {
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
                            floods[index(neighbor)] = currentTime + Mathf.random(1000 * 5, 1000 * 10);
                        } else if (time <= currentTime) {
                            setFlood(neighbor, config.floodTiles.get(0), multiplier);
                            updates++;
                        }
                    }
                } else {
                    if (neighborBuild.team != Team.crux) {
                        var currentTier = config.floodTiles.find(f -> f.block == build.block);
                        if (currentTier != null) {
                            neighborBuild.damage(currentTier.damage * multiplier);
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
        updatedTiles.computeIfAbsent(floodTile.block, k -> new Seq<>()).add(tile.pos());

        floods[index(tile)] = Time.millis() + (long) (floodTile.evolveTime * 1000 / multiplier)
                + Mathf.random(1000 * 1, 1000 * 5);
    }

    @Listener
    private void onBlockDestroyed(BlockDestroyEvent event) {
        var tile = event.tile;
        var block = tile.build;

        if (block != null && block.team == Team.crux) {
            floods[index(tile)] = Time.millis() + config.floodTiles.get(0).evolveTime * 1000;
        }
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

        float elapsedMinutes = (Time.millis() - startedAt) / 1000 / 60;
        float destroyedCores = (cores - Team.crux.cores().size) + suppressed.size();

        return 1f + (destroyedCores / cores) + (0.01f * elapsedMinutes) + (isNight ? 2 : 0);
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
            Events.fire(new EventType.GameOverEvent(Team.sharded));
        }
    }
}
