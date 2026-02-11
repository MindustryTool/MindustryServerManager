package plugin.gamemode.flood;

import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import arc.Core;
import arc.math.Mathf;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Time;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.game.EventType;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.storage.CoreBlock;
import plugin.annotations.Gamemode;
import plugin.annotations.Listener;
import plugin.annotations.Persistence;
import plugin.annotations.Schedule;

@Gamemode("flood")
public class FloodGamemode {

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class FloodTile {
        Block block;
        float damage;
        long evolveTime;
    }

    private ConcurrentHashMap<Building, Long> suppressed = new ConcurrentHashMap<>();
    private long[] floods = new long[0];

    @Persistence("flood/config.json")
    private Seq<FloodTile> floodTiles = Seq.with(
            new FloodTile(Blocks.conveyor, 32f, 1000 * 5),
            new FloodTile(Blocks.titaniumConveyor, 64f, 1000 * 10),
            new FloodTile(Blocks.copperWall, 64f, 1000 * 20),
            new FloodTile(Blocks.titaniumWall, 64f, 1000 * 40),
            new FloodTile(Blocks.plastaniumWall, 64f, 1000 * 80),
            new FloodTile(Blocks.thoriumWall, 64f, 1000 * 140),
            new FloodTile(Blocks.phaseWall, 64f, 1000 * 200)//
    );

    private FloodTile nextTier(Building building) {
        var found = false;

        for (var tile : floodTiles) {
            if (found) {
                return tile;
            }

            if (tile.block == building.block) {
                found = true;
            }
        }

        return null;
    }

    @Schedule(fixedRate = 1, unit = TimeUnit.SECONDS)
    public void updateUI() {
        for (var core : suppressed.keySet()) {
            Call.label("Suppressed", 1.1f, core.getX(), core.getY());
        }
    }

    @Listener
    private void onBlockDestroy(EventType.BlockDestroyEvent event) {
        var build = event.tile.build;
        if (build != null && build.team == Team.crux) {
            removeFlood(build.tile);
        }
    }

    @Schedule(fixedDelay = 1, unit = TimeUnit.SECONDS)
    private void updateUnitDamgeOnFlood() {
        for (var unit : Groups.unit) {
            if (unit.team == Team.crux) {
                continue;
            }

            var tile = unit.tileOn();

            if (tile == null || tile.build == null) {
                continue;
            }
            var floodTile = floodTiles.find(t -> t.block == tile.build.block);

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
                Core.app.post(() -> tile.build.damage(floodTiles.get(0).damage));
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
                setFlood(tile, floodTiles.get(0));
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
            var spreadToNext = false;

            if (evolveAt < Time.millis()) {
                var next = nextTier(build);
                if (next != null) {
                    spreadToNext = true;
                    setFlood(tile, next);
                }
            }

            for (Tile neighbor : around(tile.build)) {
                var neighborBuild = neighbor.build;

                if (neighborBuild == null) {
                    if (spreadToNext && neighbor.block() != Blocks.air) {
                        setFlood(neighbor, floodTiles.get(0));
                    }
                } else {
                    if (neighborBuild.team != Team.crux) {
                        var currentTier = floodTiles.find(f -> f.block == build.block);
                        if (currentTier != null) {
                            Core.app.post(() -> neighborBuild.damage(currentTier.damage));
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
        floods[index(tile)] = Time.millis() + floodTile.evolveTime;
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

    @Listener
    public void onWorldLoadEnd(EventType.WorldLoadEndEvent event) {
        floods = new long[Vars.world.width() * Vars.world.height()];
    }

    @Listener
    public void onBuildDamage(EventType.BuildDamageEvent event) {
        var build = event.build;

        if (build.team == Team.crux && build.block instanceof CoreBlock) {
            suppressed.put(build, Time.millis() + 60 * 5);
        }
    }
}
