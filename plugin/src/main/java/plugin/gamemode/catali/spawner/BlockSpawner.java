package plugin.gamemode.catali.spawner;

import arc.math.Mathf;
import arc.util.Log;
import lombok.RequiredArgsConstructor;
import mindustry.Vars;
import mindustry.game.Team;
import mindustry.gen.Groups;
import plugin.annotations.Component;
import plugin.annotations.Lazy;
import plugin.gamemode.catali.CataliConfig;

@RequiredArgsConstructor
@Component
@Lazy
public class BlockSpawner {

    private final CataliConfig config;

    public void spawn(Team team) {
        for (var entry : config.blockSpawnChance) {
            var block = entry.block;
            var spawnChance = entry.chance;

            if (Mathf.chance(spawnChance)) {
                var mapSize = Vars.world.width() * Vars.world.height();
                var spawnCount = mapSize * 0.05;

                var totalBuildCount = Groups.build.size();

                if (totalBuildCount >= spawnCount) {
                    break;
                }

                var tile = SpawnerHelper.getSpawnTile(block.size);

                Log.info("Spawning block @ at tile @", block, tile);

                if (tile == null) {
                    return;
                }

                tile.setBlock(block, team);
                Log.info("Spawned block @ at tile @", block, tile);
            }
        }
    }
}
