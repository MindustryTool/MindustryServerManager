package plugin.handler;

import java.lang.ref.WeakReference;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import lombok.Data;
import mindustry.Vars;
import mindustry.game.EventType.BlockBuildEndEvent;
import mindustry.game.EventType.BlockDestroyEvent;
import mindustry.gen.Building;
import mindustry.gen.Player;
import mindustry.world.Tile;
import plugin.PluginEvents;

public class SnapshotHandler {
    private static final ConcurrentHashMap<Integer, BuiltByData> builtBy = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Building, Player> buildingToPlayer = new ConcurrentHashMap<>();

    @Data
    public static class BuiltByData {
        public final Player player;
        public final WeakReference<Building> building;
        public final Instant builtTime;
    }

    public static void init() {
        PluginEvents.on(BlockBuildEndEvent.class, SnapshotHandler::onBlockBuildEnd);
        PluginEvents.on(BlockDestroyEvent.class, SnapshotHandler::onBlockDestroy);
    }

    public static Optional<Player> getBuiltBy(Building building) {
        return Optional.ofNullable(buildingToPlayer.get(building));
    }

    private static void onBlockDestroy(BlockDestroyEvent event) {
        var tile = event.tile;

        if (tile == null) {
            return;
        }

        int index = tileIndex(tile);

        builtBy.remove(index);

        if (tile.build != null) {
            buildingToPlayer.remove(tile.build);
        }
    }

    private static int tileIndex(Tile tile) {
        return tile.x + tile.y * Vars.world.width();
    }

    private static void onBlockBuildEnd(BlockBuildEndEvent event) {
        var unit = event.unit;
        var tile = event.tile;

        if (unit == null) {
            return;
        }

        if (!unit.isPlayer()) {
            return;
        }

        if (tile == null) {
            return;
        }
        var building = tile.build;

        if (building == null) {
            return;
        }

        var player = unit.getPlayer();

        int index = tileIndex(tile);

        var exists = builtBy.get(index);

        if (exists != null) {
            var b = exists.building.get();
            if (b != null) {
                if (b.block == building.block) {
                    return;
                }
            }
        }

        builtBy.put(index, new BuiltByData(player, new WeakReference<>(building), Instant.now()));
        buildingToPlayer.put(building, player);
    }

    public static void unload() {
        builtBy.clear();
        buildingToPlayer.clear();
    }
}
