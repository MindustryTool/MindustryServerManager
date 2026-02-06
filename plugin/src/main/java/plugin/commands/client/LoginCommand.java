package plugin.commands.client;

import arc.util.Log;
import dto.LoginDto;
import mindustry.gen.Call;
import plugin.annotations.Component;
import plugin.service.ApiGateway;
import plugin.service.I18n;
import plugin.type.Session;
import plugin.commands.PluginClientCommand;
import plugin.core.Registry;

@Component
public class LoginCommand extends PluginClientCommand {
    public LoginCommand() {
        setName("login");
        setDescription("Login");
        setAdmin(false);

    }

    @Override
    public void handle(Session session) {
        try {
            LoginDto login = Registry.get(ApiGateway.class).login(session.player);

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
