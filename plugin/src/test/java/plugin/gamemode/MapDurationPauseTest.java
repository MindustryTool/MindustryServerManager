package plugin.gamemode;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import mindustry.Vars;
import mindustry.core.GameState;
import mindustry.game.EventType.PlayEvent;
import mindustry.game.EventType.StateChangeEvent;
import mindustry.gen.Groups;
import plugin.maprecord.MapRecordService;

public class MapDurationPauseTest {

    @BeforeAll
    static void setupVars() {
        Vars.state = new GameState();
        Groups.init();
    }

    @Test
    void testMapRecordServicePauseTracking() throws InterruptedException {
        Vars.state.set(GameState.State.playing);
        MapRecordService service = new MapRecordService(null, null);
        service.onPlayEvent(new PlayEvent());

        Instant initialStart = service.getMapStartedAt();

        // Pause state transition
        Vars.state.set(GameState.State.paused);
        service.onStateChangeEvent(new StateChangeEvent(GameState.State.playing, GameState.State.paused));

        Thread.sleep(100);

        // Resume state transition
        Vars.state.set(GameState.State.playing);
        service.onStateChangeEvent(new StateChangeEvent(GameState.State.paused, GameState.State.playing));

        Instant newStart = service.getMapStartedAt();
        assertTrue(newStart.isAfter(initialStart));
        long pausedDiff = Duration.between(initialStart, newStart).toMillis();
        assertTrue(pausedDiff >= 80, "Paused difference should be at least 80ms, got: " + pausedDiff);
    }
}
