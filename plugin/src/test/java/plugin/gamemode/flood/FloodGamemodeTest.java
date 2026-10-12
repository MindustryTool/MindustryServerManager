package plugin.gamemode.flood;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import arc.util.Time;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.UnitTypes;
import mindustry.core.ContentLoader;
import mindustry.core.GameState;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.world.Tile;

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
}
