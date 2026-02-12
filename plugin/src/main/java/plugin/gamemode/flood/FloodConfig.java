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
            new FloodTile(Blocks.conveyor, 32f, 5),
            new FloodTile(Blocks.titaniumConveyor, 64f, 15),
            new FloodTile(Blocks.armoredConveyor, 96f, 25),
            new FloodTile(Blocks.scrapWall, 128f, 30),
            new FloodTile(Blocks.copperWall, 160f, 80),
            new FloodTile(Blocks.titaniumWall, 192f, 100),
            new FloodTile(Blocks.plastaniumWall, 224f, 150),
            new FloodTile(Blocks.thoriumWall, 256f, 180),
            new FloodTile(Blocks.phaseWall, 288f, 200), //
            new FloodTile(Blocks.surgeWall, 230f, 200)//
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
