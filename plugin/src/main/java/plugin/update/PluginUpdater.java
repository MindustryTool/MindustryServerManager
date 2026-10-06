package plugin.update;

import java.security.MessageDigest;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import arc.files.Fi;
import arc.util.Log;
import lombok.RequiredArgsConstructor;
import mindustry.Vars;
import mindustry.game.EventType.GameOverEvent;
import mindustry.game.EventType.PlayerJoin;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import plugin.PluginEvents;
import plugin.annotations.Component;
import plugin.annotations.Listener;
import plugin.annotations.Schedule;
import plugin.event.UnloadServerEvent;
import plugin.gamemode.Gamemode;
import plugin.gateway.ApiGateway;
import plugin.utils.Tr;
import plugin.utils.Utils;

@Component
@RequiredArgsConstructor
public class PluginUpdater {
    public static final long SANDBOX_RESTART_DELAY_MS = 30 * 60 * 1000L;

    private final ApiGateway apiGateway;
    private final Fi jar = Vars.modDirectory.child("plugin.jar");

    private boolean isScheduled = false;
    private long scheduledRestartTime = -1;
    private boolean waitingForGameOver = false;
    private boolean isRestarting = false;

    private String pendingHash = null;
    private String currentJarHash = null;

    private synchronized String getCurrentHash() {
        if (currentJarHash != null) {
            return currentJarHash;
        }

        try {

            if (!jar.exists()) {
                throw new RuntimeException("plugin.jar does not exist in mods directory");
            }

            byte[] bytes = jar.readBytes();
            currentJarHash = sha256(bytes);

            return currentJarHash;
        } catch (Exception e) {
            Log.err("Failed to compute current plugin hash", e);
            return null;
        }
    }

    private static String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Schedule(delay = 1, fixedDelay = 1, unit = TimeUnit.MINUTES)
    public void checkUpdate() {
        String bundleHash = sendGetPluginVersion();

        if (bundleHash == null) {
            Log.err("[red]Bundle hash query failed; will retry next cycle");
            return;
        }

        String currentHash = getCurrentHash();

        if (currentHash == null) {
            Log.err("[red]Cannot read current plugin jar for hash comparison");
            return;
        }

        if (Objects.equals(bundleHash, pendingHash)) {
            return;
        }

        if (Objects.equals(bundleHash, currentHash)) {
            return;
        }

        pendingHash = bundleHash;
        Log.info("[purple]New plugin bundle hash detected: @, scheduling restart...", bundleHash);
        scheduleRestart();
    }

    /**
     * Send the WS get-plugin-version request and return the hash string, or null on
     * failure.
     */
    private String sendGetPluginVersion() {
        try {
            return apiGateway
                    .sendRequest("get-plugin-version", null, String.class)
                    .get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            Log.err("Failed to query bundle hash from manager", e);
            return null;
        }
    }

    @Schedule(delay = 10, fixedDelay = 10, unit = TimeUnit.SECONDS)
    public void checkScheduledRestart() {
        if (isRestarting) {
            return;
        }

        boolean hasUpdatesOrScheduled = pendingHash != null || isScheduled;
        if (!hasUpdatesOrScheduled) {
            return;
        }

        if (Groups.player.isEmpty()) {
            performUpdateAndRestart();
            return;
        }

        if (scheduledRestartTime > 0 && System.currentTimeMillis() >= scheduledRestartTime) {
            Log.info("[purple]Sandbox restart countdown expired, restarting...");
            performUpdateAndRestart();
        }
    }

    @Listener
    private void onGameOver(GameOverEvent event) {
        if (isRestarting) {
            return;
        }

        if ((waitingForGameOver || isScheduled) && (hasPendingUpdates() || isScheduled)) {
            Log.info("[purple]GameOverEvent received with pending restart, restarting...");
            performUpdateAndRestart();
        }
    }

    @Listener
    private void onPlayerJoin(PlayerJoin event) {
        if (event == null || event.player == null) {
            return;
        }

        if (hasPendingUpdates() || isScheduled) {
            notifyPlayer(event.player);
        }
    }

    public void broadcastRestartNotice() {
        if (scheduledRestartTime > 0) {
            int minutes = getRemainingMinutes();
            Utils.forEachPlayerLocale((locale, players) -> {
                String msg = Tr.t(locale, "update.restart_scheduled_sandbox", "minutes", minutes);
                for (var p : players) {
                    p.sendMessage(msg);
                }
            });
        } else if (waitingForGameOver) {
            Utils.forEachPlayerLocale((locale, players) -> {
                String msg = Tr.t(locale, "update.restart_pending_gameover");
                for (var p : players) {
                    p.sendMessage(msg);
                }
            });
        }
    }

    public void notifyPlayer(Player player) {
        if (player == null) {
            return;
        }

        if (scheduledRestartTime > 0) {
            int minutes = getRemainingMinutes();
            player.sendMessage(Tr.t(player, "update.restart_join_sandbox", "minutes", minutes));
        } else if (waitingForGameOver) {
            player.sendMessage(Tr.t(player, "update.restart_join_gameover"));
        } else if (isScheduled) {
            player.sendMessage(Tr.t(player, "update.restart_scheduled"));
        }
    }

    public int getRemainingMinutes() {
        if (scheduledRestartTime <= 0) {
            return 0;
        }

        long remainingMs = scheduledRestartTime - System.currentTimeMillis();
        return Math.max(1, (int) Math.ceil(remainingMs / 60000.0));
    }

    public synchronized void performUpdateAndRestart() {
        if (isRestarting) {
            return;
        }
        isRestarting = true;

        Log.info("[purple]Updating controller plugin hash to: @, then restarting...", pendingHash);
        currentJarHash = null;
        pendingHash = null;

        try {
            byte[] bytes = apiGateway.sendRequest("download-plugin", null, byte[].class).get(30, TimeUnit.SECONDS);
            jar.writeBytes(bytes);
            Log.info(bytes.length + " bytes written to plugin.jar");
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            Log.err("Failed to update plugin bundle from manager", e);
        }

        PluginEvents.fire(new UnloadServerEvent(true));
    }

    public void scheduleRestart() {
        if (isScheduled) {
            return;
        }

        isScheduled = true;
        if (isSandboxMode()) {
            if (scheduledRestartTime <= 0) {
                scheduledRestartTime = System.currentTimeMillis() + SANDBOX_RESTART_DELAY_MS;
            }
        } else {
            waitingForGameOver = true;
        }
    }

    public boolean isWaitingForGameOver() {
        return waitingForGameOver;
    }

    public long getScheduledRestartTime() {
        return scheduledRestartTime;
    }

    public boolean isScheduled() {
        return isScheduled;
    }

    public boolean hasPendingUpdates() {
        return pendingHash != null;
    }

    public boolean isSandboxMode() {
        if (Gamemode.active("sandbox")) {
            return true;
        }
        return Vars.state != null && Vars.state.rules != null
                && Vars.state.rules.mode() == mindustry.game.Gamemode.sandbox;
    }
}
