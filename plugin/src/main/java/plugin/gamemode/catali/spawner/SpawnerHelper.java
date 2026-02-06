package plugin.gamemode.catali.spawner;

import arc.math.Mathf;
import arc.struct.Seq;
import mindustry.Vars;
import mindustry.gen.Groups;
import mindustry.world.Tile;

public class SpawnerHelper {
    public static Tile getSpawnTile(float occupiedSize) {
        int worldWidth = Vars.world.width();
        int worldHeight = Vars.world.height();

        final int chunkSize = 16;

        int chunksX = Mathf.ceil(worldWidth / (float) chunkSize);
        int chunksY = Mathf.ceil(worldHeight / (float) chunkSize);
        int chunkCount = chunksX * chunksY;

        Seq<Integer> indices = new Seq<>(chunkCount);
        for (int i = 0; i < chunkCount; i++) {
            indices.set(i, i);
        }

        indices.shuffle();

        for (int index : indices) {
            int chunkX = index % chunksX;
            int chunkY = index / chunksX;

            int startX = chunkX * chunkSize;
            int startY = chunkY * chunkSize;

            // Try multiple random spots inside the chunk
            for (int i = 0; i < 4; i++) {
                int x = startX + Mathf.random(chunkSize - 1);
                int y = startY + Mathf.random(chunkSize - 1);

                Tile tile = Vars.world.tile(x, y);
                if (tile == null || tile.block() != null)
                    continue;

                if (Groups.unit.intersect(x, y, occupiedSize, occupiedSize).any()) {
                    continue;
                }

                return tile;
            }
        }

        return null;
    }
}
