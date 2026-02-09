package plugin.gamemode.catali.spawner;

import arc.Core;
import arc.math.Mathf;
import arc.math.geom.Vec2;
import arc.util.Log;
import lombok.RequiredArgsConstructor;
import mindustry.game.Team;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import plugin.annotations.Gamemode;
import plugin.gamemode.catali.CataliConfig;
import plugin.gamemode.catali.CataliGamemode;
import plugin.gamemode.catali.ai.BossAi;
import plugin.gamemode.catali.ai.SwarmAi;

@Gamemode("catali")
@RequiredArgsConstructor
public class UnitSpawner {

    private final CataliConfig config;

    public void spawn(CataliGamemode gamemode, Team team) {
        var canSpawnBoss = gamemode.findStrongestTeam().map(t -> t.level.level >= config.bossStartSpawnLevel)
                .orElse(false);

        for (var entry : config.unitSpawnChance) {
            if (Mathf.chance(entry.chances)) {
                var count = team.data().unitCount;
                var maxCount = Math.min(Groups.player.size(), 4) * 20;

                if (count >= maxCount) {
                    break;
                }

                var largestUnit = entry.units.stream().max((a, b) -> Float.compare(a.hitSize, b.hitSize)).orElse(null);

                if (largestUnit == null) {
                    Log.warn("No unit found in spawn chance entry: @", entry);
                    return;
                }

                var tile = SpawnerHelper.getSpawnTile(largestUnit.hitSize);

                if (tile == null) {
                    return;
                }

                for (int i = 0; i < entry.units.size(); i++) {
                    var unit = entry.units.get(i);

                    Core.app.post(() -> {
                        Unit u = unit.create(team);

                        if (canSpawnBoss && Mathf.chance(0.1)) {
                            u.controller(new BossAi());
                        } else {
                            var position = new Vec2(tile.worldx(), tile.worldy());
                            u.controller(new SwarmAi(position));
                        }

                        u.set(tile.worldx() + Mathf.random(largestUnit.hitSize),
                                tile.worldy() + Mathf.random(largestUnit.hitSize));
                        u.add();
                    });
                }
            }
        }
    }
}
