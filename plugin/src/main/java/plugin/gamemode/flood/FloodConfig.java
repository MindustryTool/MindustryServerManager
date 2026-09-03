package plugin.gamemode.flood;

import arc.struct.Seq;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import mindustry.content.Blocks;
import mindustry.gen.Building;
import mindustry.world.Block;
import plugin.annotations.Configuration;

@Configuration("flood/config.json")
public class FloodConfig {
    public Seq<FloodTile> floodTiles = Seq.with(
            new FloodTile(Blocks.conveyor, 9f, 20),
            new FloodTile(Blocks.titaniumConveyor, 12f, 80),
            new FloodTile(Blocks.armoredConveyor, 15f, 125),
            new FloodTile(Blocks.scrapWall, 18f, 160),
            new FloodTile(Blocks.copperWall, 24f, 200),
            new FloodTile(Blocks.titaniumWall, 30f, 300),
            new FloodTile(Blocks.plastaniumWall, 45f, 400),
            new FloodTile(Blocks.thoriumWall, 60f, 600),
            new FloodTile(Blocks.phaseWall, 90f, 800), //
            new FloodTile(Blocks.surgeWall, 120f, 1100)//
    );

    public float suppressThreshold = 500f;
    public long suppressTime = 1000 * 5;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class FloodTile {
        Block block;
        float damage;
        long evolveTime;
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
