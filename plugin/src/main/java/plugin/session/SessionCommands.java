package plugin.session;

import arc.util.Log;
import dto.LoginDto;
import lombok.RequiredArgsConstructor;
import mindustry.gen.Call;
import plugin.annotations.ClientCommand;
import plugin.annotations.Component;
import plugin.gateway.ApiGateway;
import plugin.utils.I18n;

@Component
@RequiredArgsConstructor
public class SessionCommands {

    private final ApiGateway apiGateway;

    @ClientCommand(name = "admin", description = "Toogle admin permisison", admin = false)
    public void admin(Session session) {
        if (session.isAdmin()) {
            session.player.admin = !session.player.admin;
            if (session.player.admin) {
                session.player.sendMessage(I18n.t(session, "[green]Admin on"));
            } else {
                session.player.sendMessage(I18n.t(session, "[red]Admin off"));
            }
        } else {
            session.player.sendMessage(I18n.t(session, "@You are not an admin"));
        }
    }

    @ClientCommand(name = "login", description = "Login", admin = false)
    public void login(Session session) {
        try {
            LoginDto login = apiGateway.login(session.player);

            var loginLink = login.getLoginLink();

            if (loginLink != null && !loginLink.isEmpty()) {
                Call.openURI(session.player.con, loginLink);
            } else {
                session.player.sendMessage(I18n.t(session.locale,
                        "@Already logged in"));
            }
        } catch (Exception e) {
            Log.err("Failed to login", e);
        }
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
