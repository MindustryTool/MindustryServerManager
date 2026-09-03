package plugin.update;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import arc.Core;
import arc.files.Fi;
import arc.struct.Seq;
import arc.util.Log;
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
import plugin.utils.Tr;
import plugin.utils.Utils;

@Component
public class PluginUpdater {
    public static final long SANDBOX_RESTART_DELAY_MS = TimeUnit.MINUTES.toMillis(30);

    private final Seq<PluginData> plugins = Seq.with(//
            new PluginData("controller", "plugin.jar", "MindustryTool", "MindustryServerManager", "plugin")//
    );

    // Maps each plugin to the new updatedAt value to be written after a successful download
    private final Map<PluginData, String> pendingUpdates = new LinkedHashMap<>();

    private boolean isScheduled = false;
    private long scheduledRestartTime = -1;
    private boolean waitingForGameOver = false;
    private boolean isRestarting = false;

    public boolean scheduleRestart() {
        isScheduled = true;
        if (isSandboxMode()) {
            if (scheduledRestartTime <= 0) {
                scheduledRestartTime = System.currentTimeMillis() + SANDBOX_RESTART_DELAY_MS;
            }
        } else {
            waitingForGameOver = true;
        }
        return true;
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
        return !pendingUpdates.isEmpty();
    }

    public boolean isSandboxMode() {
        if (Gamemode.active("sandbox")) {
            return true;
        }
        return Vars.state != null && Vars.state.rules != null
                && Vars.state.rules.mode() == mindustry.game.Gamemode.sandbox;
    }

    @Schedule(delay = 1, fixedDelay = 5, unit = TimeUnit.MINUTES)
    public void checkUpdate() {
        var needUpdate = false;

        for (PluginData pluginData : plugins) {
            if (pendingUpdates.containsKey(pluginData)) {
                needUpdate = true;
                continue;
            }

            // Fix #2: catch per-plugin errors so one failure doesn't abort the whole loop
            try {
                PluginData.PluginVersion version = pluginData.getPluginVersion();

                // Fix #7: guard against a null PluginVersion (e.g. malformed API response)
                if (version == null) {
                    Log.err("[red]Received null version response for plugin @", pluginData.getId());
                    continue;
                }

                String updatedAt = version.getUpdatedAt();
                String currentUpdatedAt = readCurrentUpdatedAt(pluginData);

                if (Objects.equals(updatedAt, currentUpdatedAt)) {
                    continue;
                }

                // Fix #3: don't persist the version here — only do so after a successful download
                pendingUpdates.put(pluginData, updatedAt);
                needUpdate = true;
            } catch (Exception e) {
                Log.err("Failed to check version for plugin @: @", pluginData.getId(), e.getMessage());
            }
        }

        if (!needUpdate && !isScheduled) {
            return;
        }

        // If no player is online, update immediately
        if (Groups.player.isEmpty()) {
            performUpdateAndRestart();
            return;
        }

        // Otherwise, schedule restart according to gamemode
        if (!pendingUpdates.isEmpty() && scheduledRestartTime <= 0 && !waitingForGameOver) {
            if (isSandboxMode()) {
                scheduledRestartTime = System.currentTimeMillis() + SANDBOX_RESTART_DELAY_MS;
                waitingForGameOver = false;
            } else {
                waitingForGameOver = true;
                scheduledRestartTime = -1;
            }
            broadcastRestartNotice();
        }
    }

    @Schedule(delay = 10, fixedDelay = 10, unit = TimeUnit.SECONDS)
    public void checkScheduledRestart() {
        if (isRestarting) {
            return;
        }

        boolean hasUpdatesOrScheduled = !pendingUpdates.isEmpty() || isScheduled;
        if (!hasUpdatesOrScheduled) {
            return;
        }

        // If all players left, restart immediately
        if (Groups.player.isEmpty()) {
            performUpdateAndRestart();
            return;
        }

        // Sandbox countdown expired
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

        if ((waitingForGameOver || isScheduled) && (!pendingUpdates.isEmpty() || isScheduled)) {
            Log.info("[purple]GameOverEvent received with pending restart, restarting...");
            performUpdateAndRestart();
        }
    }

    @Listener
    private void onPlayerJoin(PlayerJoin event) {
        if (event == null || event.player == null) {
            return;
        }

        if (!pendingUpdates.isEmpty() || isScheduled) {
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

        Vars.modDirectory.mkdirs();

        boolean anyUpdated = false;

        // Iterate over a snapshot so we can safely remove entries on success
        for (Map.Entry<PluginData, String> entry : new ArrayList<>(pendingUpdates.entrySet())) {
            PluginData pluginData = entry.getKey();
            String updatedAt = entry.getValue();

            Log.info("[purple]Downloading plugin: @/@/@", pluginData.getOwner(), pluginData.getRepo(),
                    pluginData.getTag());

            try {
                byte[] data = pluginData.download();
                Fi pluginFile = Vars.modDirectory.child(pluginData.getPath());

                if (pluginFile.exists() && pluginFile.isDirectory()) {
                    pluginFile.deleteDirectory();
                }

                if (pluginFile.exists()) {
                    pluginFile.delete();
                }

                pluginFile.writeBytes(data);

                // Fix #3: write the new version only after the file is successfully on disk
                // Fix #5: remove from pending so it won't be re-downloaded on the next cycle
                Core.settings.put(pluginData.getId() + "-version", updatedAt);
                pendingUpdates.remove(pluginData);
                anyUpdated = true;
            } catch (Exception e) {
                Log.err("Failed to download plugin @: @", pluginData.getId(), e.getMessage());
            }
        }

        // Fix #6: one forceSave() after all puts, not one per plugin
        if (anyUpdated) {
            Core.settings.forceSave();
        }

        Log.info("[purple]Plugin updated, restarting...");
        PluginEvents.fire(new UnloadServerEvent(true));
    }

    private String readCurrentUpdatedAt(PluginData plugin) {
        return Core.settings.getString(plugin.getId() + "-version", null);
    }
}
