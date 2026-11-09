package plugin.hub;

import java.net.InetAddress;
import arc.util.Log;
import arc.util.Scaling;
import arc.util.Timer;
import common.server.Server;
import lombok.RequiredArgsConstructor;
import mindustry.gen.Call;
import mindustry.gen.Iconc;
import mindustry.gen.Player;
import mindustry.ui.builder.UiBuilder;
import mindustry.ui.builder.UiBuilder.TableBuilder;
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
        Server server = ctx.getState();
        if (server == null) {
            return;
        }

        this.title = Tr.t(ctx.getSession().locale, "hub.redirect.title");

        String serverId = server.getId() != null ? server.getId().toString() : "";
        boolean isOnline = server.getMapName() != null;
        String statusText = isOnline
                ? "[green]● " + Tr.t(ctx.getSession().locale, "hub.list.players", "players", server.getPlayers())
                : "[scarlet]● " + Tr.t(ctx.getSession().locale, "hub.list.server_offline");

        TableBuilder card = UiBuilder.table().background("pane").margin(12f).align("top").align("left");
        card.width(MenuContext.SAFE_CARD_WIDTH);

        card.add(UiBuilder.image("net-server-" + serverId)
                .placeholder("nomap")
                .scaling(Scaling.fit)
                .width(266f)
                .height(115f)
                .align("center"))
            .row();

        card.add(UiBuilder.label("[yellow]" + server.getName())
                .wrap()
                .align("left")
                .labelAlign("left")
                .width(266f)
                .padTop(6f))
            .row();

        card.add(UiBuilder.label(statusText)
                .align("left")
                .labelAlign("left")
                .width(266f)
                .padTop(2f))
            .row();

        if (isOnline) {
            String details = Tr.t(ctx.getSession().locale, "hub.list.gamemode", "mode", server.getMode().toLowerCase())
                    + " • " + Tr.t(ctx.getSession().locale, "hub.list.map", "map", server.getMapName());
            card.add(UiBuilder.label("[lightgray]" + details)
                    .wrap()
                    .align("left")
                    .labelAlign("left")
                    .width(266f)
                    .padTop(2f))
                .row();
        }

        if (server.getDescription() != null && !server.getDescription().trim().isEmpty()) {
            card.add(UiBuilder.label("[gray]" + server.getDescription())
                    .wrap()
                    .align("left")
                    .labelAlign("left")
                    .width(266f)
                    .padTop(3f))
                .row();
        }

        ctx.add(card);
        ctx.row();

        ctx.table(actions -> {
            actions.align("center");
            String confirmAct = ctx.registerAction((sess, st, res) -> onServerChoose(sess.player, serverId, server.getName()));
            actions.add(UiBuilder.button("[green]" + Iconc.right + " " + Tr.t(ctx.getSession().locale, "hub.redirect.yes"))
                    .clicked(confirmAct)
                    .width(130f)
                    .height(36f)
                    .pad(3f));

            String cancelAct = ctx.registerAction((sess, st, res) -> {
                // Closes dialog naturally by returning
            });
            actions.add(UiBuilder.button("[scarlet]" + Iconc.cancel + " " + Tr.t(ctx.getSession().locale, "hub.redirect.no"))
                    .clicked(cancelAct)
                    .width(130f)
                    .height(36f)
                    .pad(3f));
        }).padTop(6f);
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
