package plugin.menus;

import mindustry.gen.Call;
import mindustry.gen.Player;
import plugin.ServerControl;
import plugin.handler.ApiGateway;
import plugin.type.Session;

import java.net.InetAddress;

import arc.util.Log;
import dto.ServerDto;

public class ServerRedirectMenu extends PluginMenu<ServerDto> {
    @Override
    public void build(Session session, ServerDto serverData) {
        this.title = "Redirect";
        this.description = "Do you want to go to server: " + serverData.getName();

        text("[red]No");
        option("[green]Yes", (p, s) -> onServerChoose(p.player, s.getId().toString(), s.getName()));
    }

    public void onServerChoose(Player player, String id, String name) {
        ServerControl.backgroundTask("Server Choose", () -> {
            try {
                player.sendMessage(String.format(
                        "[green]Starting server [white]%s, [white]this can take up to 1 minutes, please wait", name));
                Log.info(String.format("Send host command to server %s %S", name, id));
                var data = ApiGateway.host(id);
                player.sendMessage("[green]Redirecting");
                Call.sendMessage(
                        String.format("%s [green]redirecting to server [white]%s, use [green]/servers[white] to follow",
                                player.coloredName(), name));

                String host = "";
                int port = 6567;

                var colon = data.lastIndexOf(":");

                if (colon > 0) {
                    host = data.substring(0, colon);
                    port = Integer.parseInt(data.substring(colon + 1).trim());
                } else {
                    host = data;
                }

                Log.info("Redirecting " + player.name + " to " + host + ":" + port);

                Call.connect(player.con, InetAddress.getByName(host.trim()).getHostAddress(), port);
            } catch (Throwable e) {
                player.sendMessage("Error: Can not load server");
                e.printStackTrace();
            }
        });
    }
}
