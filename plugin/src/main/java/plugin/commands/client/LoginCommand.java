package plugin.commands.client;

import arc.util.Log;
import dto.LoginDto;
import mindustry.gen.Call;
import plugin.handler.ApiGateway;
import plugin.handler.I18n;
import plugin.type.Session;
import plugin.commands.PluginCommand;

public class LoginCommand extends PluginCommand {
    public LoginCommand() {
        setName("login");
        setDescription("Login");
        setAdmin(false);

    }

    @Override
    public void handleClient(Session session) {
        try {
            LoginDto login = ApiGateway.login(session.player);

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
}
