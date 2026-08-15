package plugin.trail;

import plugin.annotations.ClientCommand;
import plugin.annotations.Component;
import plugin.session.Session;

@Component
public class TrailCommands {

    @ClientCommand(name = "trail", description = "Toggle trail", admin = false)
    public void trail(Session session) {
        new TrailMenu().send(session, 0);
    }
}