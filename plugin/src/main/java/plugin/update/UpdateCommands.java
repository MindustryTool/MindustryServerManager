package plugin.update;

import lombok.RequiredArgsConstructor;
import mindustry.gen.Call;
import plugin.annotations.ClientCommand;
import plugin.annotations.Component;
import plugin.session.Session;

@Component
@RequiredArgsConstructor
public class UpdateCommands {

    private final PluginUpdater updater;

    @ClientCommand(name = "restart", description = "Restart the server")
    public void restart(Session session) {
        Call.sendMessage("[cyan]Server scheduled for a restart.");
        updater.scheduleRestart();
    }
}