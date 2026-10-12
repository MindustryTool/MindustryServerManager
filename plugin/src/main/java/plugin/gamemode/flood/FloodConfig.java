package plugin.gamemode.flood;

import arc.struct.Seq;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import mindustry.content.Blocks;
import mindustry.content.UnitTypes;
import mindustry.gen.Building;
import mindustry.type.UnitType;
import mindustry.world.Block;
import plugin.annotations.Configuration;

@Configuration("flood/config.json")
public class FloodConfig {
    public Seq<FloodTile> floodTiles = Seq.with(
            new FloodTile(Blocks.conveyor, 9f, 20, UnitTypes.atrax, 0.050f),
            new FloodTile(Blocks.titaniumConveyor, 12f, 80, UnitTypes.atrax, 0.045f),
            new FloodTile(Blocks.armoredConveyor, 15f, 125, UnitTypes.atrax, 0.040f),
            new FloodTile(Blocks.scrapWall, 18f, 160, UnitTypes.atrax, 0.035f),
            new FloodTile(Blocks.copperWall, 24f, 200, UnitTypes.spiroct, 0.030f),
            new FloodTile(Blocks.titaniumWall, 30f, 300, UnitTypes.spiroct, 0.025f),
            new FloodTile(Blocks.plastaniumWall, 45f, 400, UnitTypes.spiroct, 0.020f),
            new FloodTile(Blocks.thoriumWall, 60f, 600, UnitTypes.arkyid, 0.015f),
            new FloodTile(Blocks.phaseWall, 90f, 800, UnitTypes.arkyid, 0.010f),
            new FloodTile(Blocks.surgeWall, 120f, 1100, UnitTypes.toxopid, 0.005f)
    );

    public float suppressThreshold = 500f;
    public long suppressTime = 1000 * 5;
    public long coreExplosionTime = 120_000L;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class FloodTile {
        Block block;
        float damage;
        long evolveTime;
        UnitType unitType;
        float spawnChance;

        public FloodTile(Block block, float damage, long evolveTime) {
            this(block, damage, evolveTime, null, 0f);
        }
    }

    public FloodTile nextTier(Building building) {
        var found = false;

        for (var tile : floodTiles) {
            if (found) {
                return tile;
            }

            if (tile.block == building.block) {
                found = true;
            }
        }

        return null;
    }

    public FloodTile lastTier() {
        if (floodTiles == null || floodTiles.isEmpty()) {
            return null;
        }
        return floodTiles.peek();
    }
}
