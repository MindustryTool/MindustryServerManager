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
            if (tile == null || tile.solid()) {
                continue;
            }

            // if (Groups.unit.intersect(tile.worldx(), tile.worldy(), occupiedSize,
            // occupiedSize).any()) {
            // continue;
            // }

            return tile;
        }

        return null;
    }
}
