package plugin.menus;

import mindustry.gen.Call;
import mindustry.gen.Player;
import plugin.Control;
import plugin.Registry;
import plugin.handler.ApiGateway;
import plugin.handler.I18n;
import plugin.type.Session;
import plugin.utils.Utils;

import java.net.InetAddress;

import arc.util.Log;
import dto.ServerDto;

public class ServerRedirectMenu extends PluginMenu<ServerDto> {

    public ServerRedirectMenu() {
    }

    @Override
    public void build(Session session, ServerDto serverData) {
        this.title = I18n.t(session.locale, "@Redirect");
        this.description = I18n.t(session.locale,
                "@Do you want to go to server: ", serverData.getName());

        text(I18n.t(session.locale, "[red]", "@No"));
        option(I18n.t(session.locale, "[green]", "@Yes"),
                (p, s) -> onServerChoose(p.player, s.getId().toString(), s.getName()));
    }

    public void onServerChoose(Player player, String id, String name) {
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
                            "@redirecting to server ", "[white]", name, ", ", "@use ", "[green]", "/servers", "[white]",
                            " ", "@to follow");
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
                player.sendMessage(I18n.t(Utils.parseLocale(player.locale()),
                        "[scarlet]", "@Error: ", "@Can not load server"));
                e.printStackTrace();
            }
        });
    }
}
