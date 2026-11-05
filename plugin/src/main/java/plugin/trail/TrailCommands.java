package plugin.trail;

import lombok.RequiredArgsConstructor;
import plugin.annotations.ClientCommand;
import plugin.annotations.Component;
import plugin.session.Session;

@Component
@RequiredArgsConstructor
public class TrailCommands {

    private final TrailMenu trailMenu;

    @ClientCommand(name = "trail", description = "Toggle trail", admin = false)
    public void trail(Session session) {
        trailMenu.send(session, 0);
    }
}
