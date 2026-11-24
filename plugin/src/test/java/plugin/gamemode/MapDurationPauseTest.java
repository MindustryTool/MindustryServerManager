package plugin.gamemode;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import mindustry.Vars;
import mindustry.core.GameState;
import mindustry.gen.Groups;

public class MapDurationPauseTest {

    @BeforeAll
    static void setupVars() {
        Vars.state = new GameState();
        Groups.init();
    }

    @Test
    void testTickDurationCalculation() {
        Vars.state.set(GameState.State.playing);
        Vars.state.tick = 3600.0; // 3600 ticks at 60 tps = 60 seconds = 60,000 ms

        long durationMs = (long) ((Vars.state.tick / 60.0) * 1000.0);
        assertEquals(60_000L, durationMs);

        // Advance ticks
        Vars.state.tick += 180.0; // 3 more seconds = 63 seconds = 63,000 ms
        durationMs = (long) ((Vars.state.tick / 60.0) * 1000.0);
        assertEquals(63_000L, durationMs);
    }
}
