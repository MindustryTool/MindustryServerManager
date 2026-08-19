package plugin.hub;

import plugin.utils.Utils;

import java.net.InetAddress;

import arc.util.Log;
import dto.ServerDto;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import plugin.Tasks;
import plugin.core.Registry;
import plugin.gateway.ApiGateway;
import plugin.utils.Tr;

public class ServerUtils {

    public static void redirectAll(ServerDto server) {
        Groups.player.each(player -> redirect(player, server));
    }

    public static void redirect(Player player, ServerDto server) {
        String id = server.getId().toString();
        String name = server.getName();

        Tasks.io("Server Choose", () -> {
            try {
                player.sendMessage(Tr.t(Utils.parseLocale(player.locale()),
                        "hub.redirect.starting", "server", name));
                Log.info(String.format("Send host command to server %s %S", name, id));

                var data = Registry.get(ApiGateway.class).hostRemoteServer(id);

                player.sendMessage(Tr.t(Utils.parseLocale(player.locale()), "hub.redirect.redirecting"));

                Utils.forEachPlayerLocale((locale, players) -> {
                    String msg = Tr.t(locale, "hub.redirect.announce",
                            "player", player.coloredName(), "server", name);
                    for (var p : players) {
                        p.sendMessage(msg);
                    }
                });

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
            } catch (Exception e) {
                player.sendMessage(Tr.t(Utils.parseLocale(player.locale()), "hub.redirect.error"));
                Log.err("Failed to redirect player: " + e.getMessage());
            }
        });
    }
}
