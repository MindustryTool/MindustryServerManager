package plugin.gamemode.catali.spawner;

import arc.math.Mathf;
import arc.util.Log;
import lombok.RequiredArgsConstructor;
import mindustry.Vars;
import mindustry.game.Team;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import plugin.annotations.Gamemode;
import plugin.gamemode.catali.CataliConfig;
import plugin.gamemode.catali.CataliGamemode;
import plugin.gamemode.catali.ai.FlyingRadiusAI;
import plugin.gamemode.catali.ai.GroundRadiusAI;

@Gamemode("catali")
@RequiredArgsConstructor
public class UnitSpawner {

    private final CataliConfig config;

    public void spawn(CataliGamemode gamemode, Team team) {
        for (var entry : config.unitSpawnChance) {
            if (Mathf.chance(entry.chances)) {
                var count = team.data().unitCount;
                var maxCount = Math.min(Groups.player.size(), 4) * 20;

                if (count >= maxCount) {
                    break;
                }

                var tile = SpawnerHelper.getSpawnTile(1);

                if (tile == null) {
                    return;
                }

                var largestUnit = entry.units.stream().max((a, b) -> Float.compare(a.hitSize, b.hitSize)).orElse(null);

                if (largestUnit == null) {
                    Log.warn("No unit found in spawn chance entry: @", entry);
                    return;
                }

                for (int i = 0; i < entry.units.size(); i++) {
                    var unit = entry.units.get(i);

                    Unit u = unit.create(team);
                    var spawnX = tile.worldx() + Mathf.random(largestUnit.hitSize / Vars.tilesize);
                    var spawnY = tile.worldy() + Mathf.random(largestUnit.hitSize / Vars.tilesize);
                    var radius = 50 * Vars.tilesize;

                    u.controller(unit.flying //
                            ? new FlyingRadiusAI(spawnX, spawnY, radius)
                            : new GroundRadiusAI(spawnX, spawnY, radius));
                    u.set(spawnX, spawnY);
                    u.add();
                }
            }
        }
    }
}
