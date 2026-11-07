package plugin.gamemode.flood;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import arc.Core;
import arc.mock.MockApplication;
import arc.struct.Seq;
import mindustry.core.World;
import mindustry.game.EventType.BlockDestroyEvent;
import mindustry.world.Block;

import arc.util.Time;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.UnitTypes;
import mindustry.core.ContentLoader;
import mindustry.core.GameState;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.world.Tile;
import mindustry.world.Tiles;

public class FloodGamemodeTest {

    private FloodConfig config;
    private FloodGamemode gamemode;

    @BeforeEach
    void setUp() {
        if (Vars.content == null) {
            Vars.content = new ContentLoader();
            Vars.content.createBaseContent();
        }
        if (Vars.state == null) {
            Vars.state = new GameState();
        }
        Vars.state.set(GameState.State.playing);

        config = new FloodConfig();
        gamemode = new FloodGamemode(config);
    }

    @Test
    void testConfigDefaultsAndConstructors() {
        assertEquals(120_000L, config.coreExplosionTime);
        assertNotNull(config.floodTiles);
        assertEquals(10, config.floodTiles.size);

        for (var tile : config.floodTiles) {
            assertNotNull(tile.block);
            assertTrue(tile.damage > 0f);
            assertNotNull(tile.unitType);
            assertTrue(tile.spawnChance > 0f);
        }

        var customTile3 = new FloodConfig.FloodTile(Blocks.conveyor, 10f, 30L);
        assertNull(customTile3.unitType);
        assertEquals(0f, customTile3.spawnChance);

        var customTile5 = new FloodConfig.FloodTile(Blocks.conveyor, 10f, 30L, UnitTypes.crawler, 0.05f);
        assertEquals(UnitTypes.crawler, customTile5.unitType);
        assertEquals(0.05f, customTile5.spawnChance);
    }

    @Test
    void testSuppressionDeadlineLifecycleAndReset() {
        Tile tile = new Tile(0, 0);
        Building core = Building.create();
        core.tile = tile;
        core.block = Blocks.coreShard;
        core.team = Team.crux;
        tile.build = core;

        gamemode.damageReceived.put(core, config.suppressThreshold + 100f);
        gamemode.updateSuppress();

        assertTrue(gamemode.suppressed.containsKey(core));
        assertTrue(gamemode.suppressionDeadlines.containsKey(core));
        long initialDeadline = gamemode.suppressionDeadlines.get(core);

        // Next second with continued damage: deadline MUST NOT be pushed forward
        gamemode.damageReceived.put(core, config.suppressThreshold + 100f);
        gamemode.updateSuppress();
        assertEquals(initialDeadline, gamemode.suppressionDeadlines.get(core));

        // When suppression expires (simulated by setting expiry in the past)
        gamemode.suppressed.put(core, Time.millis() - 1000L);
        gamemode.updateSuppress();

        assertFalse(gamemode.suppressed.containsKey(core));
        assertFalse(gamemode.suppressionDeadlines.containsKey(core));

        // Re-suppressing sets a fresh new deadline
        gamemode.damageReceived.put(core, config.suppressThreshold + 100f);
        gamemode.updateSuppress();

        assertTrue(gamemode.suppressed.containsKey(core));
        assertTrue(gamemode.suppressionDeadlines.containsKey(core));
    }

    @AfterEach
    void tearDown() {
        Vars.world = null;
        Core.app = null;
    }

    @Test
    void testDeferredCallbackIgnoredAfterSpreaderRecreated() {
        Vars.headless = true;
        Vars.world = new World();
        Tiles tiles = Vars.world.resize(10, 10);
        Tile tile = new Tile(2, 2);
        tiles.set(2, 2, tile);

        Block tier1Block = new Block("test-t1") {};
        tier1Block.id = 100;
        Block tier2Block = new Block("test-t2") {};
        tier2Block.id = 101;

        FloodConfig customConfig = new FloodConfig();
        customConfig.floodTiles = Seq.with(
                new FloodConfig.FloodTile(tier1Block, 10f, 20),
                new FloodConfig.FloodTile(tier2Block, 20f, 40)
        );

        FloodGamemode customGamemode = new FloodGamemode(customConfig);
        FloodSpreader oldSpreader = new FloodSpreader(customConfig);
        oldSpreader.reset(10, 10);
        customGamemode.spreader = oldSpreader;

        Building build = Building.create();
        build.tile = tile;
        build.block = tier2Block;
        build.team = Team.crux;
        tile.build = build;

        List<Runnable> postedTasks = new ArrayList<>();
        Core.app = new MockApplication() {
            @Override
            public void post(Runnable runnable) {
                postedTasks.add(runnable);
            }
        };

        BlockDestroyEvent event = new BlockDestroyEvent(tile);
        customGamemode.onBlockDestroyed(event);

        assertEquals(1, postedTasks.size());

        // Simulate world reload / new match: spreader is replaced
        FloodSpreader newSpreader = new FloodSpreader(customConfig);
        newSpreader.reset(10, 10);
        customGamemode.spreader = newSpreader;

        // Executing the stale task must be safely discarded
        postedTasks.get(0).run();
        assertEquals(Blocks.air, tile.block());

        // Verify that matching active spreader does execute
        postedTasks.clear();
        tile.build = build;
        customGamemode.spreader = oldSpreader;
        customGamemode.onBlockDestroyed(event);
        assertEquals(1, postedTasks.size());

        postedTasks.get(0).run();
        assertEquals(tier1Block, tile.block());
    }
}