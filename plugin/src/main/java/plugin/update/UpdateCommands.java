package plugin.update;

import lombok.RequiredArgsConstructor;
import plugin.annotations.ClientCommand;
import plugin.annotations.Component;
import plugin.session.Session;
import plugin.utils.Tr;
import plugin.utils.Utils;

@Component
@RequiredArgsConstructor
public class UpdateCommands {

    private final PluginUpdater updater;

    @ClientCommand(name = "restart", description = "Restart the server")
    public void restart(Session session) {
        Utils.forEachPlayerLocale((locale, players) -> {
            String msg = Tr.t(locale, "admin.restart_scheduled");
            for (var p : players) {
                p.sendMessage(msg);
            }
        });
        updater.scheduleRestart();
    }
}