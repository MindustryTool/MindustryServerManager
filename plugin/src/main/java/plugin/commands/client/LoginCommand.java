package plugin.commands.client;

import arc.util.Log;
import dto.LoginDto;
import mindustry.gen.Call;
import mindustry.gen.Player;
import plugin.handler.ApiGateway;
import plugin.commands.PluginCommand;

public class LoginCommand extends PluginCommand {
    public LoginCommand() {
        setName("login");
        setDescription("Login");
    }

    @Override
    public void handleClient(Player player) {
        try {
            LoginDto login = ApiGateway.login(player);

            var loginLink = login.getLoginLink();

            if (loginLink != null && !loginLink.isEmpty()) {
                Call.openURI(player.con, loginLink);
            } else {
                player.sendMessage("Already logged in");
            }
        } catch (Exception e) {
            Log.err("Failed to login", e);
        }
    }
}
