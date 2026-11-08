package plugin.hub;

import java.net.InetAddress;
import arc.util.Log;
import arc.util.Timer;
import common.server.Server;
import lombok.RequiredArgsConstructor;
import mindustry.gen.Call;
import mindustry.gen.Player;
import mindustry.ui.builder.UiBuilder;
import plugin.Tasks;
import plugin.annotations.Component;
import plugin.gateway.ApiGateway;
import plugin.menus.MenuContext;
import plugin.menus.ServerMenu;
import plugin.utils.Tr;
import plugin.utils.Utils;

@Component
@RequiredArgsConstructor
public class ServerRedirectMenu extends ServerMenu<Server> {

    private final ApiGateway apiGateway;

    @Override
    public void build(MenuContext<Server> ctx) {
        Server serverData = ctx.getState();
        if (serverData == null) {
            return;
        }

        this.title = Tr.t(ctx.getSession().locale, "hub.redirect.title");

        ctx.label(Tr.t(ctx.getSession().locale, "hub.redirect.confirm", "server", serverData.getName()));
        ctx.row();

        ctx.table(actions -> {
            String confirmAct = ctx.registerAction((sess, st, res) -> onServerChoose(sess.player, serverData.getId().toString(), serverData.getName()));
            actions.add(UiBuilder.button("[green]" + Tr.t(ctx.getSession().locale, "hub.redirect.yes"))
                .clicked(confirmAct)
                .width(MenuContext.SAFE_CARD_WIDTH)
                .height(36f)
                .pad(4f));
        });
    }

    public void onServerChoose(Player player, String id, String name) {
        Tasks.io("Server Choose", () -> {
            try {
                player.sendMessage(Tr.t(Utils.parseLocale(player.locale()), "hub.redirect.starting", "server", name));
                Log.info(String.format("Send host command to server %s %S", name, id));

                var data = apiGateway.hostRemoteServer(id);
                player.sendMessage(Tr.t(Utils.parseLocale(player.locale()), "hub.redirect.redirecting"));

                Utils.forEachPlayerLocale((locale, players) -> {
                    String msg = Tr.t(locale, "hub.redirect.announce", "player", player.coloredName(), "server", name);
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
                InetAddress finalHost = InetAddress.getByName(host.trim());
                int finalPort = port;

                Timer.schedule(() -> {
                    try {
                        Call.connect(player.con, finalHost.getHostAddress(), finalPort);
                    } catch (Exception e) {
                        Log.err("Failed to redirect player", e);
                    }
                }, 1.5f);
            } catch (Exception e) {
                Log.err("Failed to choose server", e);
                player.sendMessage(Tr.t(Utils.parseLocale(player.locale()), "hub.redirect.error"));
            }
        });
    }
}
