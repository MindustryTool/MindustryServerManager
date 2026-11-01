package plugin.core;

import arc.Core;
import arc.util.Log;
import arc.util.Time;
import mindustry.Vars;
import mindustry.core.GameState.State;
import mindustry.game.EventType.PlayerJoin;
import mindustry.game.EventType.PlayerLeave;
import mindustry.gen.Groups;
import plugin.annotations.Component;
import plugin.annotations.Destroy;
import plugin.annotations.Init;
import plugin.annotations.Listener;

@Component
public class IdleTickThrottler {

    public static final long DEFAULT_THROTTLE_SLEEP_MS = 100L;

    private long throttleSleepMs = DEFAULT_THROTTLE_SLEEP_MS;

    @Init
    public void init() {
        Time.setDeltaProvider(this::provideDelta);
        Log.info("IdleTickThrottler initialized with @ms sleep", throttleSleepMs);
    }

    @Destroy
    public void destroy() {
        Time.setDeltaProvider(() -> Math.min(Core.graphics.getDeltaTime() * 60f, 3f));
        Log.info("IdleTickThrottler destroyed, default delta provider restored");
    }

    @Listener
    public void onPlayerLeave(PlayerLeave event) {
        if (Core.app != null) {
            Core.app.post(this::checkAutoPause);
        } else {
            checkAutoPause();
        }
    }

    @Listener
    public void onPlayerJoin(PlayerJoin event) {
        if (Vars.state != null && Vars.state.isPaused()) {
            Vars.state.set(State.playing);
            Log.info("Player joined: playing");
        }
    }

    public void checkAutoPause() {
        if (Vars.state != null && Vars.state.isPlaying() && Groups.player != null && Groups.player.isEmpty()) {
            Vars.state.set(State.paused);
            Log.info("No player: paused");
        }
    }

    public float provideDelta() {
        if (shouldThrottle()) {
            try {
                Thread.sleep(throttleSleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return Math.min(Core.graphics.getDeltaTime() * 60f, 3f);
    }

    public boolean shouldThrottle() {
        boolean paused = Vars.state != null && Vars.state.isPaused();
        int playerCount = Groups.player != null ? Groups.player.size() : 0;
        return shouldThrottle(paused, playerCount);
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
