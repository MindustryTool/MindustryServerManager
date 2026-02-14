package plugin.gamemode.catali.spawner;

import arc.math.Mathf;
import mindustry.Vars;
import mindustry.world.Tile;

public class SpawnerHelper {
    public static Tile getSpawnTile(float occupiedSize) {
        int worldWidth = Vars.world.width();
        int worldHeight = Vars.world.height();

        for (int i = 0; i < 4; i++) {
            int x = Mathf.random(0, worldWidth - 1);
            int y = Mathf.random(0, worldHeight - 1);

            Tile tile = Vars.world.tile(x, y);

            if (!isTileSafe(tile, occupiedSize)) {
                continue;
            }

            for (int offsetX = 0; offsetX < occupiedSize; offsetX++) {
                for (int offsetY = 0; offsetY < occupiedSize; offsetY++) {
                    var nextTile = Vars.world.tile(x + offsetX, y + offsetY);

                    if (!isTileSafe(nextTile, occupiedSize)) {
                        return null;
                    }
                }
            }

            // if (Groups.unit.intersect(tile.worldx(), tile.worldy(), occupiedSize,
            // occupiedSize).any()) {
            // continue;
            // }

            return tile;
        }

        return null;
    }

    public static boolean isTileSafe(Tile tile, float occupiedSize) {
        if (tile == null || tile.solid()) {
            return false;
        }

        for (int offsetX = 0; offsetX < occupiedSize; offsetX++) {
            for (int offsetY = 0; offsetY < occupiedSize; offsetY++) {
                var nextTile = Vars.world.tile(tile.x + offsetX, tile.y + offsetY);

                if (nextTile == null || nextTile.solid()) {
                    return false;
                }
            }
        }

        return true
        // && !Groups.unit.intersect(tile.worldx(), tile.worldy(), occupiedSize,
        // occupiedSize).any()
        ;
    }
}
