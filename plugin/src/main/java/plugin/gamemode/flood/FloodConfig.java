package plugin.gamemode.flood;

import arc.struct.Seq;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import mindustry.content.Blocks;
import mindustry.world.Block;
import plugin.annotations.Configuration;

@Configuration("flood/config.json")
public class FloodConfig {
    public Seq<FloodTile> floodTiles = Seq.with(
            new FloodTile(Blocks.conveyor, 32f, 1000 * 10),
            new FloodTile(Blocks.titaniumConveyor, 64f, 1000 * 20),
            new FloodTile(Blocks.copperWall, 64f, 1000 * 40),
            new FloodTile(Blocks.titaniumWall, 64f, 1000 * 80),
            new FloodTile(Blocks.plastaniumWall, 64f, 1000 * 160),
            new FloodTile(Blocks.thoriumWall, 64f, 1000 * 200),
            new FloodTile(Blocks.phaseWall, 64f, 1000 * 280)//
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
}
