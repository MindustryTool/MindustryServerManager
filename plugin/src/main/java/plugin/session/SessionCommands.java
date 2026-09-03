package plugin.session;

import arc.util.Log;
import dto.LoginDto;
import lombok.RequiredArgsConstructor;
import mindustry.gen.Call;
import plugin.annotations.ClientCommand;
import plugin.annotations.Component;
import plugin.gateway.ApiGateway;
import plugin.utils.Tr;

@Component
@RequiredArgsConstructor
public class SessionCommands {

    private final ApiGateway apiGateway;

    @ClientCommand(name = "admin", description = "Toogle admin permisison", admin = true)
    public void admin(Session session) {
        if (session.isAdmin()) {
            session.player.admin = !session.player.admin;
            if (session.player.admin) {
                session.player.sendMessage(Tr.t(session, "session.admin_on"));
            } else {
                session.player.sendMessage(Tr.t(session, "session.admin_off"));
            }
        } else {
            session.player.sendMessage(Tr.t(session, "session.not_admin"));
        }
    }

    @ClientCommand(name = "login", description = "Login", admin = false)
    public void login(Session session) {
        if (session.isLoggedIn()) {
            session.player.sendMessage(Tr.t(session.locale, "session.already_logged_in"));
            return;
        }

        new LoginMenu().send(session, null);
    }

    @ClientCommand(name = "me", description = "Display your info", admin = false)
    public void me(Session session) {
        session.player.sendMessage(SessionUtils.getInfoString(session, session.getData()));
    }

    @ClientCommand(name = "pinfo", description = "Display player info", admin = false)
    public void pinfo(Session session) {
        new PlayerInfoMenu().send(session, session);
    }
}
