package plugin;

import java.util.HashSet;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import arc.Core;
import arc.files.Fi;
import arc.struct.Seq;
import arc.util.Log;
import mindustry.Vars;
import mindustry.gen.Groups;
import plugin.annotations.Component;
import plugin.annotations.Schedule;

@Component
public class PluginUpdater {
    private final Seq<PluginData> plugins = Seq.with(//
            new PluginData("controller", "plugin.jar", "MindustryTool", "MindustryServerManager", "plugin")//
    );

    private final HashSet<PluginData> pendingUpdates = new HashSet<>();

    private boolean isScheduled = false;

    public boolean scheduleRestart() {
        return isScheduled = true;
    }

    @Schedule(delay = 5, fixedDelay = 5, unit = TimeUnit.MINUTES)
    public void checkUpdate() {
        var needUpdate = false;

        for (PluginData pluginData : plugins) {
            if (pendingUpdates.contains(pluginData)) {
                needUpdate = true;
                continue;
            }

            String updatedAt = pluginData.getPluginVersion().getUpdatedAt();
            String currentUpdatedAt = readCurrentUpdatedAt(pluginData);

            if (Objects.equals(updatedAt, currentUpdatedAt)) {
                continue;
            }

            pendingUpdates.add(pluginData);
            needUpdate = true;

            writeUpdatedAt(pluginData, updatedAt);
        }

        if (!needUpdate && !isScheduled) {
            return;
        }

        // Update when no player is online
        if (!Groups.player.isEmpty()) {
            return;
        }

        Vars.modDirectory.mkdirs();

        for (PluginData pluginData : plugins) {
            if (!pendingUpdates.contains(pluginData)) {
                continue;
            }

            Log.info("[cyan]Downloading plugin: @/@/@", pluginData.getOwner(), pluginData.getRepo(),
                    pluginData.getTag());

            byte[] data = pluginData.download();
            Fi pluginFile = Vars.modDirectory.child(pluginData.getPath());

            if (pluginFile.exists() && pluginFile.isDirectory()) {
                pluginFile.deleteDirectory();
            }

            if (pluginFile.exists()) {
                pluginFile.delete();
            }

            pluginFile.writeBytes(data);
        }

        Log.info("[cyan]Plugin updated, restarting...");

        Core.app.exit();
    }

    private String readCurrentUpdatedAt(PluginData plugin) {
        return Core.settings.getString(plugin.getId() + "-version", null);
    }

    private void writeUpdatedAt(PluginData plugin, String updatedAt) {
        Core.settings.put(plugin.getId() + "-version", updatedAt);
    }
}
