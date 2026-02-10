package plugin.commands.server;

import mindustry.gen.Call;
import plugin.PluginUpdater;
import plugin.commands.PluginServerCommand;

public class RestartServerCommand extends PluginServerCommand {
    private final PluginUpdater updater;

    public RestartServerCommand(PluginUpdater updater) {
        this.updater = updater;

        setName("restart");
        setDescription("Restart the server.");
    }

    @Override
    public void handle() {
        Call.sendMessage("[cyan]Server scheduled for a restart.");
        updater.scheduleRestart();
    }
}
