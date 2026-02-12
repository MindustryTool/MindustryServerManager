package plugin.gamemode.flood;

import java.util.ArrayDeque;
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
import mindustry.game.EventType;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Iconc;
import mindustry.world.Tile;
import plugin.annotations.Gamemode;
import plugin.annotations.Init;
import plugin.annotations.Listener;
import plugin.annotations.Schedule;
import plugin.annotations.Trigger;
import plugin.gamemode.flood.FloodConfig.FloodTile;

@Gamemode("flood")
@RequiredArgsConstructor
public class FloodGamemode {

    private final FloodConfig config;

    private final ConcurrentHashMap<Building, Float> damageReceived = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Building, Long> suppressed = new ConcurrentHashMap<>();

    private long startedAt = 0;
    private long[] floods = new long[0];
    private int cores = 1;

    @Init
    private void applyRules() {
        Vars.state.rules.enemyCoreBuildRadius = 0f;
        Team.crux.rules().extraCoreBuildRadius = 0f;

        Log.info("Flood rules applied");
    }

    @Listener
    private void onPlayEvent(EventType.PlayEvent event) {
        applyRules();
    }

    @Listener
    public void onWorldLoadEnd(EventType.WorldLoadEndEvent event) {
        floods = new long[Vars.world.width() * Vars.world.height()];
        cores = Math.max(1, Team.crux.cores().size);
        startedAt = Time.millis();
        suppressed.clear();
        damageReceived.clear();

        applyRules();
    }

    @Schedule(fixedRate = 1, unit = TimeUnit.SECONDS)
    public void updateUI() {
        for (var core : suppressed.keySet()) {
            Call.label("[scarlet]" + Iconc.warning + " *Suppressed*", 1.1f, core.getX(), core.getY());
        }
        Call.infoPopup("Flood: " + getFloodMultiplier() * 100 + "%\n" +
                "Suppressed: " + suppressed.size() + "/" + cores, 1.1f, Align.center | Align.right, 4, 4, 4, 4);
    }

    @Listener
    private void onBlockDestroy(EventType.BlockDestroyEvent event) {
        var build = event.tile.build;
        if (build != null && build.team == Team.crux) {
            removeFlood(build.tile);
        }
    }

    @Trigger(EventType.Trigger.update)
    private void updateCore() {
        for (var core : Team.crux.cores()) {
            var damaged = core.maxHealth - core.health;
            core.maxHealth(100000000);
            core.heal();
            if (damaged > 0) {
                damageReceived.put(core, damageReceived.getOrDefault(core, 0f) + damaged);
            }
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

    @Schedule(fixedDelay = 1, unit = TimeUnit.SECONDS)
    public void update() {
        float startedAt = Time.millis();

        Seq<Tile> tiles = new Seq<>();

        suppressed.entrySet().removeIf(e -> e.getValue() < Time.millis());

        for (var core : Team.crux.cores()) {
            if (suppressed.containsKey(core)) {
                continue;
            }

            tiles.add(around(core));
        }

        for (var tile : tiles) {
            if (tile.build != null && tile.build.team != Team.crux) {
                Core.app.post(() -> tile.build.damage(config.floodTiles.get(0).damage));
            }
        }

        if (tiles.isEmpty()) {
            return;
        }

        var number = Mathf.random(0, tiles.size);

        boolean[] spreaded = new boolean[floods.length];

        for (int i = 0; i < number; i++) {
            var tile = tiles.random();
            if (tile.build == null) {
                setFlood(tile, config.floodTiles.get(0));
            } else {
                spread(tile, spreaded);
            }
        }

        var elapsed = Time.millis() - startedAt;

        if (elapsed > 1000) {
            Log.warn("Flood spreaded in " + elapsed + "ms");
        }
    }

    private void spread(Tile start, boolean[] spreaded) {
        ArrayDeque<Tile> queue = new ArrayDeque<>();

        spreaded[index(start)] = true;
        queue.add(start);

        while (!queue.isEmpty()) {
            Tile tile = queue.poll();

            var build = tile.build;

            // Not flood skip
            if (build == null || build.team != Team.crux) {
                continue;
            }

            var evolveAt = floods[index(tile)];

            if (evolveAt > 0 && evolveAt < Time.millis()) {
                var next = config.nextTier(build);
                if (next != null) {
                    setFlood(tile, next);
                }
            }

            for (Tile neighbor : around(tile.build)) {
                var neighborBuild = neighbor.build;

                if (neighborBuild == null) {
                    if (neighbor.block() == Blocks.air || neighbor.block().alwaysReplace) {
                        var time = floods[index(neighbor)];
                        if (time <= 0) {
                            floods[index(neighbor)] = Time.millis() + Mathf.random(1000 * 5, 1000 * 10);
                        } else if (time <= Time.millis()) {
                            setFlood(neighbor, config.floodTiles.get(0));
                        }
                    }
                } else {
                    if (neighborBuild.team != Team.crux) {
                        var currentTier = config.floodTiles.find(f -> f.block == build.block);
                        if (currentTier != null) {
                            Core.app.post(() -> neighborBuild.damage(currentTier.damage * getFloodMultiplier()));
                        }
                    } else if (!spreaded[index(neighbor)]) {
                        spreaded[index(neighbor)] = true;
                        queue.add(neighbor);
                    }
                }
            }
        }
    }

    private void setFlood(Tile tile, FloodTile floodTile) {
        Core.app.post(() -> tile.setNet(floodTile.block, Team.crux, 0));

        floods[index(tile)] = Time.millis() + (long) (floodTile.evolveTime / getFloodMultiplier())
                + Mathf.random(0, 1000 * 5);
    }

    private void removeFlood(Tile tile) {
        floods[index(tile)] = 0;
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
        cores = Math.max(cores, Team.crux.cores().size);

        var elapsedMinutes = (Time.millis() - startedAt) / 1000 / 60;
        var destroyedCores = cores - Team.crux.cores().size;

        return 1f + (destroyedCores / cores) + (0.01f * elapsedMinutes);
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
