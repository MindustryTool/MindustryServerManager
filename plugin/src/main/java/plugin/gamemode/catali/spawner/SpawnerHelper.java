package plugin.gamemode.catali.spawner;

import arc.math.Mathf;
import arc.util.Log;
import mindustry.Vars;
import mindustry.gen.Groups;
import mindustry.world.Tile;

public class SpawnerHelper {
    public static Tile getSpawnTile(float occupiedSize) {
        var mapSize = Vars.world.width() * Vars.world.height();

        var chunkSize = 16;
        var chunkCount = (int) Math.ceil(mapSize / (double) chunkSize);
        boolean[] chunks = new boolean[chunkCount];
        var attempts = 0;

        Log.info("Spawning tile for occupied size @, chunk count: @", occupiedSize, chunkCount);

        do {
            attempts++;
            var chunkIndex = Mathf.random(0, chunkCount);

            Log.info("Attempt @, chunk index: @", attempts, chunkIndex);

            if (chunks[chunkIndex]) {
                continue;
            }

            chunks[chunkIndex] = true;

            var chunkX = chunkIndex % chunkSize;
            var chunkY = chunkIndex / chunkSize;

            var spawnX = chunkX * chunkSize + Mathf.random(0, chunkSize);
            var spawnY = chunkY * chunkSize + Mathf.random(0, chunkSize);

            var tile = Vars.world.tile(spawnX, spawnY);

            if (tile == null || tile.block() != null
                    || Groups.unit.intersect(spawnX, spawnY, occupiedSize, occupiedSize).any()) {
                continue;
            }

            return tile;

        } while (attempts++ < chunkCount);

        Log.warn("Failed to find spawn tile after @ attempts", attempts);

        return null;
    }
}
