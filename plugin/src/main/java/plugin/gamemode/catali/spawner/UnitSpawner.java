package plugin.gamemode.catali.spawner;

import arc.math.Mathf;
import arc.util.Log;
import lombok.RequiredArgsConstructor;
import mindustry.game.Team;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import plugin.annotations.Component;
import plugin.annotations.Lazy;
import plugin.gamemode.catali.CataliConfig;

@Component
@Lazy
@RequiredArgsConstructor
public class UnitSpawner {

    private final CataliConfig config;

    public void spawn(Team team) {
        for (var entry : config.unitSpawnChance) {
            if (Mathf.chance(entry.chances / 100)) {
                var count = team.data().unitCount;
                var maxCount = Groups.player.size() * 30;

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

                    Unit u = unit.create(team);
                    u.set(tile.worldx() + Mathf.random(largestUnit.hitSize),
                            tile.worldy() + Mathf.random(largestUnit.hitSize));
                    u.add();

                    Log.info("Spawning unit @ at tile @", unit, tile);
                }
            }
        }
    }
}
