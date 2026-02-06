package plugin.gamemode.catali.spawner;

import arc.math.Mathf;
import arc.util.Log;
import lombok.RequiredArgsConstructor;
import mindustry.Vars;
import mindustry.game.Team;
import mindustry.gen.Groups;
import mindustry.world.Block;
import plugin.annotations.Component;
import plugin.annotations.Lazy;
import plugin.gamemode.catali.CataliConfig;

@RequiredArgsConstructor
@Component
@Lazy
public class BlockSpawner {

    private final CataliConfig config;

    public void spawn(Team team) {
        Block blockToSpawn = null;

        Log.info("Spawning block for team @", team);

        for (var entry : config.blockSpawnChance) {
            var block = entry.block;
            var spawnChance = entry.chance;

            if (Mathf.chance(spawnChance)) {
                blockToSpawn = block;
                break;
            }
        }

        if (blockToSpawn == null) {
            return;
        }

        Log.info(blockToSpawn);

        var mapSize = Vars.world.width() * Vars.world.height();
        var spawnCount = mapSize * 0.15;

        var totalBuildCount = Groups.build.size();

        Log.info("Total build count: @, spawn count: @", totalBuildCount, spawnCount);

        if (totalBuildCount >= spawnCount) {
            return;
        }

        var tile = SpawnerHelper.getSpawnTile(blockToSpawn.size);

        Log.info("Spawning block @ at tile @", blockToSpawn, tile);

        if (tile == null) {
            return;
        }

        tile.setBlock(blockToSpawn, team);
    }
}
