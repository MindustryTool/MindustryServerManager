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
import plugin.gamemode.attack.AttackRank;
import plugin.gamemode.flood.FloodRank;
import plugin.gamemode.survival.SurvivalRank;

public class MapDurationPauseTest {

    @BeforeAll
    static void setupVars() {
        Vars.state = new GameState();
        Groups.init();
    }

    @Test
    void testAttackRankPauseTracking() throws InterruptedException {
        Vars.state.set(GameState.State.playing);
        AttackRank rank = new AttackRank(null);
        rank.onPlayEvent(new PlayEvent());

        Instant initialStart = rank.getMapStartedAt();

        // Pause state transition
        Vars.state.set(GameState.State.paused);
        rank.onStateChangeEvent(new StateChangeEvent(GameState.State.playing, GameState.State.paused));

        Thread.sleep(100);

        // Resume state transition
        Vars.state.set(GameState.State.playing);
        rank.onStateChangeEvent(new StateChangeEvent(GameState.State.paused, GameState.State.playing));

        Instant newStart = rank.getMapStartedAt();
        // Since pause occurred for ~100ms, new mapStartedAt should be shifted forward in time compared to initialStart
        assertTrue(newStart.isAfter(initialStart));
        long pausedDiff = Duration.between(initialStart, newStart).toMillis();
        assertTrue(pausedDiff >= 80, "Paused difference should be at least 80ms, got: " + pausedDiff);
    }

    @Test
    void testFloodRankPauseTracking() throws InterruptedException {
        Vars.state.set(GameState.State.playing);
        FloodRank rank = new FloodRank(null);
        rank.onPlayEvent(new PlayEvent());

        Instant initialStart = rank.getMapStartedAt();

        Vars.state.set(GameState.State.paused);
        rank.onStateChangeEvent(new StateChangeEvent(GameState.State.playing, GameState.State.paused));

        Thread.sleep(100);

        Vars.state.set(GameState.State.playing);
        rank.onStateChangeEvent(new StateChangeEvent(GameState.State.paused, GameState.State.playing));

        Instant newStart = rank.getMapStartedAt();
        assertTrue(newStart.isAfter(initialStart));
    }

    @Test
    void testSurvivalRankPauseTracking() throws InterruptedException {
        Vars.state.set(GameState.State.playing);
        SurvivalRank rank = new SurvivalRank(null);
        rank.onPlayEvent(new PlayEvent());

        Instant initialStart = rank.getMapStartedAt();

        Vars.state.set(GameState.State.paused);
        rank.onStateChangeEvent(new StateChangeEvent(GameState.State.playing, GameState.State.paused));

        Thread.sleep(100);

        Vars.state.set(GameState.State.playing);
        rank.onStateChangeEvent(new StateChangeEvent(GameState.State.paused, GameState.State.playing));

        Instant newStart = rank.getMapStartedAt();
        assertTrue(newStart.isAfter(initialStart));
    }
}
