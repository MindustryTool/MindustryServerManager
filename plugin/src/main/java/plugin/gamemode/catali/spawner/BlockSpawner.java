package plugin.gamemode.catali.spawner;

import arc.Core;
import arc.math.Mathf;
import lombok.RequiredArgsConstructor;
import mindustry.Vars;
import mindustry.game.Team;
import mindustry.gen.Groups;
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
                var tile = SpawnerHelper.getSpawnTile(block.size);

                if (tile == null) {
                    return;
                }

                Core.app.post(() -> {
                    tile.setNet(block, team, 0);
                });
            }
        }
    }
}
