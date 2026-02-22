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
            new FloodTile(Blocks.conveyor, 30f, 20),
            new FloodTile(Blocks.titaniumConveyor, 40f, 80),
            new FloodTile(Blocks.armoredConveyor, 50f, 125),
            new FloodTile(Blocks.scrapWall, 60f, 160),
            new FloodTile(Blocks.copperWall, 80f, 200),
            new FloodTile(Blocks.titaniumWall, 100f, 300),
            new FloodTile(Blocks.plastaniumWall, 150f, 400),
            new FloodTile(Blocks.thoriumWall, 200f, 600),
            new FloodTile(Blocks.phaseWall, 300f, 800), //
            new FloodTile(Blocks.surgeWall, 400f, 1100)//
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
}
