package plugin.core;

import arc.Core;
import arc.util.Log;
import mindustry.Vars;
import mindustry.core.GameState.State;
import mindustry.game.EventType;
import mindustry.game.EventType.PlayerJoin;
import mindustry.game.EventType.PlayerLeave;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.gen.Groups;
import plugin.annotations.Component;
import plugin.annotations.Init;
import plugin.annotations.Listener;
import plugin.annotations.Trigger;

@Component
public class IdleTickThrottler {

    public static final long DEFAULT_THROTTLE_SLEEP_MS = 100L;

    private long throttleSleepMs = DEFAULT_THROTTLE_SLEEP_MS;

    @Init
    public void init() {
        Log.info("IdleTickThrottler initialized with @ms sleep", throttleSleepMs);
    }

    @Trigger(EventType.Trigger.update)
    public void onUpdate() {
        if (shouldThrottle()) {
            throttleSleep();
        }
    }

    @Listener
    public void onWorldLoad(WorldLoadEvent event) {
        Core.app.post(this::checkAutoPause);
    }

    @Listener
    public void onPlayerLeave(PlayerLeave event) {
        Core.app.post(this::checkAutoPause);
    }

    @Listener
    public void onPlayerJoin(PlayerJoin event) {
        if (Vars.state.isPaused()) {
            Vars.state.set(State.playing);
            Log.info("Player joined: playing");
        }
    }

    public void checkAutoPause() {
        if (Vars.state.isPlaying() && Groups.player.isEmpty()) {
            Vars.state.set(State.paused);
            Log.info("No player: paused");
        }
    }

    public void throttleSleep() {
        try {
            Thread.sleep(throttleSleepMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public boolean shouldThrottle() {
        return shouldThrottle(Vars.state.isPaused(), Groups.player.size());
    }

    public boolean shouldThrottle(boolean paused, int playerCount) {
        return paused && playerCount == 0;
    }

    public long getThrottleSleepMs() {
        return throttleSleepMs;
    }

    public void setThrottleSleepMs(long throttleSleepMs) {
        this.throttleSleepMs = throttleSleepMs;
    }
}
