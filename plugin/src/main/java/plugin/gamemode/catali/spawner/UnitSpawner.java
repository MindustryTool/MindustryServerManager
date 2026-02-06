package plugin.gamemode.catali.spawner;

import arc.math.Mathf;
import arc.util.Log;
import arc.util.Timer;
import lombok.RequiredArgsConstructor;
import mindustry.game.Team;
import mindustry.gen.Unit;
import plugin.annotations.Component;
import plugin.annotations.Lazy;
import plugin.gamemode.catali.CataliConfig;
import plugin.gamemode.catali.CataliConfig.UnitSpawnChance;

@Component
@Lazy
@RequiredArgsConstructor
public class UnitSpawner {

    private final CataliConfig config;

    public void spawn(Team team) {
        UnitSpawnChance entry = null;

        for (var item : config.unitSpawnChance) {
            if (Mathf.chance(item.chances)) {
                entry = item;
                break;
            }
        }

        if (entry == null) {
            return;
        }

        var largestUnit = entry.units.stream().max((a, b) -> Float.compare(a.hitSize, b.hitSize)).orElse(null);

        if (largestUnit == null) {
            Log.warn("No unit found in spawn chance entry: @", entry);
            return;
        }

        var tile = SpawnerHelper.getSpawnTile(largestUnit.hitSize);

        for (int i = 0; i < entry.units.size(); i++) {
            var unit = entry.units.get(i);

            Timer.schedule(() -> {
                Unit u = unit.create(team);
                u.set(tile.worldx() + Mathf.random(largestUnit.hitSize),
                        tile.worldy() + Mathf.random(largestUnit.hitSize));
                u.add();
            }, i);
        }
    }
}
