package plugin.hub;

import java.net.InetAddress;
import java.util.List;
import arc.util.Log;
import arc.util.Scaling;
import arc.util.Timer;
import common.server.Server;
import mindustry.gen.Call;
import mindustry.gen.Iconc;
import mindustry.gen.Player;
import mindustry.ui.builder.UiBuilder;
import mindustry.ui.builder.UiBuilder.PaneBuilder;
import mindustry.ui.builder.UiBuilder.TableBuilder;
import plugin.Tasks;
import plugin.annotations.Component;
import plugin.gateway.ApiGateway;
import plugin.menus.MenuContext;
import plugin.menus.ServerMenu;
import plugin.utils.Tr;
import plugin.utils.Utils;

@Component
public class ServerRedirectMenu extends ServerMenu<Server> {

    private final ApiGateway apiGateway;
    private final ServerImageService serverImageService;

    public ServerRedirectMenu(ApiGateway apiGateway, ServerImageService serverImageService) {
        this.apiGateway = apiGateway;
        this.serverImageService = serverImageService;
        this.fillScreen = true;
    }

    @Override
    public void build(MenuContext<Server> ctx) {
        Server server = ctx.getState();
        if (server == null) {
            return;
        }

        if (ctx.getSession() != null && ctx.getSession().player != null && serverImageService != null) {
            serverImageService.streamServerTextures(ctx.getSession().player, List.of(server));
        }

        this.title = Tr.t(ctx.getSession().locale, "hub.redirect.title");
        ctx.getRoot().align("top");

        String serverId = server.getId() != null ? server.getId().toString() : "";
        boolean isOnline = server.getMapName() != null;
        String statusText = isOnline
                ? "[green]● " + Tr.t(ctx.getSession().locale, "hub.list.players", "players", server.getPlayers())
                : "[scarlet]● " + Tr.t(ctx.getSession().locale, "hub.list.server_offline");

        float cardWidth = 330f;
        float cardHeight = 370f;
        float contentWidth = cardWidth - 20f;

        TableBuilder card = UiBuilder.table()
                .background("pane")
                .margin(10f)
                .align("topLeft")
                .width(cardWidth)
                .height(cardHeight);

        card.add(UiBuilder.image("net-server-" + serverId)
                .placeholder("nomap")
                .scaling(Scaling.fit)
                .width(contentWidth)
                .height(180f)
                .align("center"))
                .row();

        card.add(UiBuilder.label("[yellow]" + server.getName())
                .wrap()
                .align("left")
                .labelAlign("left")
                .width(contentWidth)
                .padTop(6f))
                .row();

        card.add(UiBuilder.label(statusText)
                .align("left")
                .labelAlign("left")
                .width(contentWidth)
                .padTop(2f))
                .row();

        if (isOnline) {
            String details = Tr.t(ctx.getSession().locale, "hub.list.gamemode", "mode", server.getMode().toLowerCase())
                    + " • " + Tr.t(ctx.getSession().locale, "hub.list.map", "map", server.getMapName());
            card.add(UiBuilder.label("[lightgray]" + details)
                    .wrap()
                        .align("left")
                    .labelAlign("left")
                    .width(contentWidth)
                    .padTop(2f))
                    .row();
        }

        if (server.getDescription() != null && !server.getDescription().trim().isEmpty()) {
            card.add(UiBuilder.label("[gray]" + server.getDescription())
                    .wrap()
                        .align("left")
                    .labelAlign("left")
                    .width(contentWidth)
                    .padTop(2f))
                    .row();
        }

        TableBuilder btnRow = UiBuilder.table().align("center");
        float btnWidth = (contentWidth - 8f) / 2f;

        String confirmAct = ctx.registerAction((sess, st, res) -> onServerChoose(sess.player, serverId, server.getName()));
        btnRow.add(UiBuilder.button("[green]" + Iconc.right + " " + Tr.t(ctx.getSession().locale, "hub.redirect.yes"))
                .clicked(confirmAct)
                .width(btnWidth)
                .height(36f));

        String cancelAct = ctx.registerAction((sess, st, res) -> {
            // Closes dialog naturally by returning
        });
        btnRow.add(UiBuilder.button("[scarlet]" + Iconc.cancel + " " + Tr.t(ctx.getSession().locale, "hub.redirect.no"))
                .clicked(cancelAct)
                .width(btnWidth)
                .height(36f)
                .padLeft(8f));

        card.add(btnRow)
                .width(contentWidth)
                .padTop(8f);

        TableBuilder contentWrapper = UiBuilder.table().align("top");
        contentWrapper.add(card);

        PaneBuilder pane = UiBuilder.pane().add(contentWrapper).grow().align("top");
        ctx.add(pane);
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
