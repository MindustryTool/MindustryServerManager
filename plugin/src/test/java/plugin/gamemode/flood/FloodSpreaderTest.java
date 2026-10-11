package plugin.gamemode.flood;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import arc.struct.Seq;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.core.ContentLoader;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.world.Block;
import mindustry.world.Tile;

public class FloodSpreaderTest {

    private FloodSpreader spreader;
    private FloodConfig config;

    @BeforeEach
    void setUp() {
        if (Vars.content == null) {
            Vars.content = new ContentLoader();
            Vars.content.createBaseContent();
        }
        config = new FloodConfig();
        spreader = new FloodSpreader(config);
        spreader.reset(10, 10);
    }

    @Test
    void testInitializationAndReset() {
        assertTrue(spreader.isInitialized());
        assertEquals(0, spreader.edgeTileCount());

        spreader.addEdgeTile(5);
        spreader.addEdgeTile(12);
        assertEquals(2, spreader.edgeTileCount());

        spreader.reset(10, 10);
        assertEquals(0, spreader.edgeTileCount());
        assertFalse(spreader.isEdgeTile(5));
        assertFalse(spreader.isEdgeTile(12));
    }

    @Test
    void testAddAndRemoveEdgeTiles() {
        assertFalse(spreader.isEdgeTile(10));
        spreader.addEdgeTile(10);
        assertTrue(spreader.isEdgeTile(10));
        assertEquals(1, spreader.edgeTileCount());

        // Duplicate addition should be idempotent
        spreader.addEdgeTile(10);
        assertEquals(1, spreader.edgeTileCount());

        // Add more tiles
        spreader.addEdgeTile(20);
        spreader.addEdgeTile(30);
        assertEquals(3, spreader.edgeTileCount());
        assertTrue(spreader.isEdgeTile(10));
        assertTrue(spreader.isEdgeTile(20));
        assertTrue(spreader.isEdgeTile(30));

        // Remove middle tile (tests swap-and-pop)
        spreader.removeEdgeTile(20);
        assertFalse(spreader.isEdgeTile(20));
        assertTrue(spreader.isEdgeTile(10));
        assertTrue(spreader.isEdgeTile(30));
        assertEquals(2, spreader.edgeTileCount());

        // Remove last tile
        spreader.removeEdgeTile(30);
        assertFalse(spreader.isEdgeTile(30));
        assertTrue(spreader.isEdgeTile(10));
        assertEquals(1, spreader.edgeTileCount());

        // Remove remaining tile
        spreader.removeEdgeTile(10);
        assertFalse(spreader.isEdgeTile(10));
        assertEquals(0, spreader.edgeTileCount());

        // Removing non-existent tile is a no-op
        spreader.removeEdgeTile(99);
        assertEquals(0, spreader.edgeTileCount());
    }

    @Test
    void testOutOfBoundsHandling() {
        spreader.addEdgeTile(-1);
        spreader.addEdgeTile(100); // map is 10x10 = 100 tiles (indices 0-99)
        assertEquals(0, spreader.edgeTileCount());

        assertFalse(spreader.isEdgeTile(-1));
        assertFalse(spreader.isEdgeTile(100));

        spreader.removeEdgeTile(-1);
        spreader.removeEdgeTile(100);
        assertEquals(0, spreader.edgeTileCount());
    }

    @Test
    void testWaveSpreadLifecycle() {
        // Empty edge tiles should not start wave
        spreader.startSpreadWave();
        assertFalse(spreader.isSpreadingWave());
        assertEquals(0, spreader.getNextWaveEdgeCount());
        assertEquals(0, spreader.getWaveQueueSize());

        // Adding edge tiles and starting wave
        spreader.addEdgeTile(15);
        spreader.addEdgeTile(25);
        assertEquals(2, spreader.edgeTileCount());

        spreader.startSpreadWave();
        assertTrue(spreader.isSpreadingWave());
        assertEquals(2, spreader.getWaveQueueSize());
        assertEquals(2, spreader.getNextWaveEdgeCount());

        // Reset resets wave state
        spreader.reset(10, 10);
        assertFalse(spreader.isSpreadingWave());
        assertEquals(0, spreader.getNextWaveEdgeCount());
        assertEquals(0, spreader.getWaveQueueSize());
        assertEquals(0, spreader.edgeTileCount());
    }

    @Test
    void testRemoveEdgeTileDuringSpreadWave() {
        spreader.addEdgeTile(10);
        spreader.addEdgeTile(20);
        spreader.addEdgeTile(30);

        spreader.startSpreadWave();
        assertTrue(spreader.isSpreadingWave());
        assertEquals(3, spreader.getWaveQueueSize());

        // Removing an edge tile removes it from edge list without altering waveQueue snapshot
        spreader.removeEdgeTile(20);
        assertEquals(2, spreader.edgeTileCount());
        assertFalse(spreader.isEdgeTile(20));
        assertTrue(spreader.isEdgeTile(10));
        assertTrue(spreader.isEdgeTile(30));
        assertEquals(3, spreader.getWaveQueueSize());
    }

    @Test
    void testWaveSpreadExecutionWithoutCrashWhenVarsWorldNull() {
        spreader.addEdgeTile(10);
        spreader.addEdgeTile(20);
        spreader.startSpreadWave();
        assertTrue(spreader.isSpreadingWave());

        // With Vars.world null, continueSpreadWave cleans up missing/invalid tiles without crashing
        spreader.continueSpreadWave(0, 1.0f);
        // Wave should finish when all elements in waveQueue are checked
        assertFalse(spreader.isSpreadingWave());
        assertEquals(0, spreader.edgeTileCount());
    }

    @Test
    void testPacedWaveAndFlushConstants() {
        assertEquals(120, FloodSpreader.MAX_SPREAD_PER_TICK);
        assertEquals(150, FloodSpreader.MAX_FLUSH_PER_WINDOW);
        assertEquals(30, FloodSpreader.MAX_FLUSH_PER_TICK);
        assertEquals(60, FloodSpreader.ADAPTIVE_FLUSH_PER_TICK);
        assertEquals(300, FloodSpreader.ADAPTIVE_BACKLOG_THRESHOLD);
    }

    @Test
    void testPerTickFlushPacing() {
        assertEquals(0, spreader.getPendingCount());

        // Enqueue 100 tiles for tier 0
        for (int i = 0; i < 100; i++) {
            spreader.enqueuePendingUpdate(0, i);
        }
        assertEquals(100, spreader.getPendingCount());

        // Tick 1 flushes exactly MAX_FLUSH_PER_TICK (30)
        spreader.tick(1.0f);
        assertEquals(70, spreader.getPendingCount());

        // Tick 2 flushes 30
        spreader.tick(1.0f);
        assertEquals(40, spreader.getPendingCount());

        // Tick 3 flushes 30
        spreader.tick(1.0f);
        assertEquals(10, spreader.getPendingCount());

        // Tick 4 flushes remaining 10
        spreader.tick(1.0f);
        assertEquals(0, spreader.getPendingCount());
    }

    @Test
    void testAdaptiveFlushOnBacklog() {
        // Enqueue 350 tiles (> ADAPTIVE_BACKLOG_THRESHOLD of 300)
        for (int i = 0; i < 350; i++) {
            spreader.enqueuePendingUpdate(0, i);
        }
        assertEquals(350, spreader.getPendingCount());

        // First tick uses ADAPTIVE_FLUSH_PER_TICK (60)
        spreader.tick(1.0f);
        assertEquals(290, spreader.getPendingCount());

        // Since remaining 290 <= 300, next tick returns to MAX_FLUSH_PER_TICK (30)
        spreader.tick(1.0f);
        assertEquals(260, spreader.getPendingCount());
    }

    @Test
    void testScanExistingFloodHandlesNullWorldGracefully() {
        // When Vars.world is null or spreader not initialized, scanExistingFlood does not throw
        spreader.scanExistingFlood(1.0f);
        assertEquals(0, spreader.edgeTileCount());

        FloodSpreader uninit = new FloodSpreader(config);
        uninit.scanExistingFlood(1.0f);
        assertEquals(0, uninit.edgeTileCount());
    }

    @Test
    void testGetDowngradeTierNullTile() {
        assertNull(spreader.getDowngradeTier(null));
    }

    @Test
    void testDowngradeTierResolution() {
        Block tier1Block = new Block("test-tier-1") {};
        tier1Block.id = 100;
        Block tier2Block = new Block("test-tier-2") {};
        tier2Block.id = 101;

        FloodConfig customConfig = new FloodConfig();
        customConfig.floodTiles = Seq.with(
                new FloodConfig.FloodTile(tier1Block, 10f, 20),
                new FloodConfig.FloodTile(tier2Block, 20f, 40)
        );

        FloodSpreader customSpreader = new FloodSpreader(customConfig);
        customSpreader.reset(10, 10);

        Tile tile = new Tile(0, 0);
        // Initially empty tile
        assertNull(customSpreader.getDowngradeTier(tile));

        // Building with lowest tier (tier 1) returns null (no lower tier)
        Building tier1Build = Building.create();
        tier1Build.tile = tile;
        tier1Build.block = tier1Block;
        tier1Build.team = Team.crux;
        tile.build = tier1Build;
        assertNull(customSpreader.getDowngradeTier(tile));

        // Building with tier 2 returns tier 1
        Building tier2Build = Building.create();
        tier2Build.tile = tile;
        tier2Build.block = tier2Block;
        tier2Build.team = Team.crux;
        tile.build = tier2Build;
        var lower = customSpreader.getDowngradeTier(tile);
        assertNotNull(lower);
        assertEquals(tier1Block, lower.block);

        // Building with tier 2 but non-crux team returns null
        tier2Build.team = Team.sharded;
        assertNull(customSpreader.getDowngradeTier(tile));

        // Building marked dead (simulating Mindustry Building.killed()) still resolves lower tier
        tier2Build.team = Team.crux;
        tier2Build.dead = true;
        var resolvedOnDeadBuild = customSpreader.getDowngradeTier(tile);
        assertNotNull(resolvedOnDeadBuild);
        assertEquals(tier1Block, resolvedOnDeadBuild.block);
    }

    @Test
    void testApplyDowngradedTileSafeHandling() {
        Tile tile = new Tile(2, 2);
        FloodConfig.FloodTile lowerTier = new FloodConfig.FloodTile(new Block("test-b") {}, 10f, 20);

        // Does not throw when inputs are null or world is null
        spreader.applyDowngradedTile(tile, lowerTier, 1.0f);
        spreader.applyDowngradedTile(null, lowerTier, 1.0f);
        spreader.applyDowngradedTile(tile, null, 1.0f);
    }
}
