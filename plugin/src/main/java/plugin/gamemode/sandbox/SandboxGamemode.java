package plugin.gamemode.sandbox;

import java.util.Arrays;
import java.util.List;

import lombok.RequiredArgsConstructor;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.game.EventType;
import mindustry.world.Block;
import mindustry.world.meta.BuildVisibility;
import plugin.annotations.Gamemode;
import plugin.annotations.Listener;

@Gamemode("sandbox")
@RequiredArgsConstructor
public class SandboxGamemode {

    @Listener
    private void onPlayEvent(EventType.PlayEvent event) {
        List<Block> blocks = Arrays.asList(
                Blocks.removeWall, Blocks.removeOre, Blocks.deepwater, Blocks.water,
                Blocks.taintedWater, Blocks.deepTaintedWater, Blocks.tar, Blocks.slag,
                Blocks.cryofluid, Blocks.stone, Blocks.craters, Blocks.charr,
                Blocks.sand, Blocks.darksand, Blocks.dirt, Blocks.mud,
                Blocks.ice, Blocks.snow, Blocks.darksandTaintedWater, Blocks.space,
                Blocks.empty, Blocks.dacite, Blocks.rhyolite, Blocks.rhyoliteCrater,
                Blocks.roughRhyolite, Blocks.regolith, Blocks.yellowStone, Blocks.redIce,
                Blocks.redStone, Blocks.denseRedStone, Blocks.arkyciteFloor, Blocks.arkyicStone,
                Blocks.redmat, Blocks.bluemat, Blocks.stoneWall, Blocks.dirtWall,
                Blocks.sporeWall, Blocks.iceWall, Blocks.daciteWall, Blocks.sporePine,
                Blocks.snowPine, Blocks.pine, Blocks.shrubs, Blocks.whiteTree,
                Blocks.whiteTreeDead, Blocks.sporeCluster, Blocks.redweed, Blocks.purbush,
                Blocks.yellowCoral, Blocks.rhyoliteVent, Blocks.carbonVent, Blocks.arkyicVent,
                Blocks.yellowStoneVent, Blocks.redStoneVent, Blocks.crystallineVent,
                Blocks.stoneVent, Blocks.basaltVent, Blocks.regolithWall,
                Blocks.yellowStoneWall, Blocks.rhyoliteWall, Blocks.carbonWall,
                Blocks.redIceWall, Blocks.ferricStoneWall, Blocks.beryllicStoneWall,
                Blocks.arkyicWall, Blocks.crystallineStoneWall, Blocks.redStoneWall,
                Blocks.redDiamondWall, Blocks.ferricStone, Blocks.ferricCraters,
                Blocks.carbonStone, Blocks.beryllicStone, Blocks.crystallineStone,
                Blocks.crystalFloor, Blocks.yellowStonePlates, Blocks.iceSnow,
                Blocks.sandWater, Blocks.darksandWater, Blocks.duneWall,
                Blocks.sandWall, Blocks.moss, Blocks.sporeMoss,
                Blocks.shale, Blocks.shaleWall, Blocks.grass,
                Blocks.salt, Blocks.coreZone, Blocks.shaleBoulder,
                Blocks.sandBoulder, Blocks.daciteBoulder, Blocks.boulder,
                Blocks.snowBoulder, Blocks.basaltBoulder, Blocks.carbonBoulder,
                Blocks.ferricBoulder, Blocks.beryllicBoulder, Blocks.yellowStoneBoulder,
                Blocks.arkyicBoulder, Blocks.crystalCluster, Blocks.vibrantCrystalCluster,
                Blocks.crystalBlocks, Blocks.crystalOrbs, Blocks.crystallineBoulder,
                Blocks.redIceBoulder, Blocks.rhyoliteBoulder, Blocks.redStoneBoulder,
                Blocks.metalFloor, Blocks.metalFloorDamaged, Blocks.metalFloor2,
                Blocks.metalFloor3, Blocks.metalFloor4, Blocks.metalFloor5,
                Blocks.basalt, Blocks.magmarock, Blocks.hotrock,
                Blocks.snowWall, Blocks.saltWall, Blocks.darkPanel1,
                Blocks.darkPanel2, Blocks.darkPanel3, Blocks.darkPanel4,
                Blocks.darkPanel5, Blocks.darkPanel6, Blocks.darkMetal,
                Blocks.metalTiles1, Blocks.metalTiles2, Blocks.metalTiles3,
                Blocks.metalTiles4, Blocks.metalTiles5, Blocks.metalTiles6,
                Blocks.metalTiles7, Blocks.metalTiles8, Blocks.metalTiles9,
                Blocks.metalTiles10, Blocks.metalTiles11, Blocks.metalTiles12,
                Blocks.metalTiles13, Blocks.metalWall1, Blocks.metalWall2,
                Blocks.metalWall3, Blocks.coloredFloor, Blocks.coloredWall,
                Blocks.characterOverlayGray, Blocks.characterOverlayWhite,
                Blocks.runeOverlay, Blocks.cruxRuneOverlay, Blocks.pebbles,
                Blocks.tendrils, Blocks.oreCopper, Blocks.oreLead,
                Blocks.oreScrap, Blocks.oreCoal, Blocks.oreTitanium,
                Blocks.oreThorium, Blocks.oreBeryllium, Blocks.oreTungsten,
                Blocks.oreCrystalThorium, Blocks.wallOreThorium,
                Blocks.wallOreBeryllium, Blocks.graphiticWall,
                // Blocks.wallOreGraphite,
                Blocks.wallOreTungsten,
                Blocks.slagCentrifuge, Blocks.heatReactor, Blocks.beamLink,
                Blocks.launchPad, Blocks.interplanetaryAccelerator,
                Blocks.illuminator, Blocks.shieldProjector,
                Blocks.largeShieldProjector, Blocks.shieldBreaker,
                Blocks.coreShard);

        blocks.stream()
                .forEach(b -> {
                    b.buildVisibility = BuildVisibility.shown;
                    b.configurable = true;
                    b.sync = true;
                    Vars.state.rules.revealedBlocks.add(b);
                });
    }
}
