package plugin.admin;

import plugin.PluginEvents;
import plugin.annotations.ClientCommand;
import plugin.annotations.Component;
import plugin.event.UnloadServerEvent;
import plugin.session.Session;
import plugin.utils.Tr;
import plugin.utils.Utils;

@Component
public class RestartCommand {

    @ClientCommand(name = "restart", description = "Restart the server")
    public void restart(Session session) {
        Utils.forEachPlayerLocale((locale, players) -> {
            String msg = Tr.t(locale, "admin.restart_scheduled");
            for (var p : players) {
                p.sendMessage(msg);
            }
        });
        PluginEvents.fire(new UnloadServerEvent(true));
    }
}
