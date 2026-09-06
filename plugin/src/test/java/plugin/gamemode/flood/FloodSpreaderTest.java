package plugin.gamemode.flood;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class FloodSpreaderTest {

    private FloodSpreader spreader;
    private FloodConfig config;

    @BeforeEach
    void setUp() {
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
}
