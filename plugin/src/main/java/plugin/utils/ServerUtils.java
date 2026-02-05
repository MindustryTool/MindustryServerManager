package plugin.utils;

import java.net.InetAddress;

import arc.util.Log;
import dto.ServerDto;
import mindustry.gen.Call;
import mindustry.gen.Player;
import plugin.Control;
import plugin.Registry;
import plugin.handler.ApiGateway;
import plugin.handler.I18n;

public class ServerUtils {

    public static void redirect(Player player, ServerDto server) {
        String id = server.getId().toString();
        String name = server.getName();

        Control.ioTask("Server Choose", () -> {
            try {
                player.sendMessage(I18n.t(Utils.parseLocale(player.locale()),
                        "[green]", "@Starting server ", "[white]", name,
                        ", ", "[white]", "@this can take up to 1 minutes, please wait"));
                Log.info(String.format("Send host command to server %s %S", name, id));

                var data = Registry.get(ApiGateway.class).host(id);

                player.sendMessage(I18n.t(Utils.parseLocale(player.locale()), "[green]", "@Redirecting"));

                Utils.forEachPlayerLocale((locale, players) -> {
                    String msg = I18n.t(locale, player.coloredName(), " ", "[green]",
                            "@redirecting to server ", "[white]", name,
                            ", ", "@use ", "[green]", "/servers", "[white]", " ", "@to follow");
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
            } catch (Throwable e) {
                player.sendMessage(I18n.t(Utils.parseLocale(player.locale()),
                        "@Error: ", "@Can not load server"));
                e.printStackTrace();
            }
        });
    }
}
