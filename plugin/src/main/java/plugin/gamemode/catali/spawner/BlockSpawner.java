package plugin.gamemode.catali.spawner;

import arc.Core;
import arc.math.Mathf;
import lombok.RequiredArgsConstructor;
import mindustry.Vars;
import mindustry.game.Team;
import mindustry.gen.Groups;
import mindustry.world.Tile;
import plugin.annotations.Gamemode;
import plugin.gamemode.catali.CataliConfig;

@RequiredArgsConstructor
@Gamemode("catali")
public class BlockSpawner {

    private final CataliConfig config;

    public void spawn(Team team) {
        var mapSize = Vars.world.width() * Vars.world.height();
        var spawnCount = mapSize * 0.20;

        var totalBuildCount = Groups.build.size();

        if (totalBuildCount >= spawnCount) {
            return;
        }

        for (var entry : config.blockSpawnChance) {
            var block = entry.block;
            var spawnChance = entry.chance;

            if (Mathf.chance(spawnChance)) {
                int worldWidth = Vars.world.width();
                int worldHeight = Vars.world.height();

                int randomX = Mathf.random(0, worldWidth - 1);
                int randomY = Mathf.random(0, worldHeight - 1);

                int x = randomX / 8 * 8;
                int y = randomY / 8 * 8;
                int clusterSize = Mathf.random(1, 10);

                for (int i = 0; i < clusterSize; i++) {
                    int offX = Mathf.random(-5, 5);
                    int offY = Mathf.random(-5, 5);

                    Tile tile = Vars.world.tile(x + offX, y + offY);

                    if (!SpawnerHelper.isTileSafe(tile, block.size)) {
                        continue;
                    }

                    Core.app.post(() -> tile.setNet(block, team, 0));
                }
            }
        }
    }
}
