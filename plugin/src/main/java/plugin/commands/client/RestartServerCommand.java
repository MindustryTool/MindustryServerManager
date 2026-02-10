package plugin.commands.client;

import mindustry.gen.Call;
import plugin.PluginUpdater;
import plugin.commands.PluginClientCommand;
import plugin.type.Session;

public class RestartServerCommand extends PluginClientCommand {
    private final PluginUpdater updater;

    public RestartServerCommand(PluginUpdater updater) {
        this.updater = updater;

        setName("restart");
        setDescription("Restart the server.");
    }

    @Override
    public void handle(Session session) {
        Call.sendMessage("[cyan]Server scheduled for a restart.");
        updater.scheduleRestart();
    }
}
