package plugin.hub;

import java.util.Comparator;
import java.util.List;
import arc.util.Log;
import common.server.Server;
import lombok.RequiredArgsConstructor;
import mindustry.gen.Iconc;
import mindustry.ui.builder.UiBuilder;
import plugin.annotations.Component;
import plugin.core.Registry;
import plugin.gateway.ApiGateway;
import plugin.menus.MenuContext;
import plugin.menus.ServerMenu;
import plugin.utils.Tr;

@Component
@RequiredArgsConstructor
public class GlobalServerListMenu extends ServerMenu<Integer> {

    private final ApiGateway apiGateway;

    @Override
    public void build(MenuContext<Integer> ctx) {
        int page = ctx.getState() != null ? ctx.getState() : 0;
        int size = 8;

        this.title = Tr.t(ctx.getSession().locale, "hub.global.title");

        try {
            PaginationRequest request = new PaginationRequest().setPage(page).setSize(size);
            List<Server> servers = apiGateway.getServers(request);

            ctx.label(Tr.t(ctx.getSession().locale, "hub.choose_server")).width(MenuContext.SAFE_WIDTH);
            ctx.row();

            ctx.pane(MenuContext.SAFE_WIDTH, 320f, paneTable -> {
                if (servers == null || servers.isEmpty()) {
                    paneTable.add(UiBuilder.label("[lightgray]" + Tr.t(ctx.getSession().locale, "hub.global.server_offline")).wrap().align("left").growX().pad(16f));
                    return;
                }

                servers.stream().sorted(Comparator.comparing(Server::getPlayers).reversed()).forEach(server -> {
                    String redirectAct = ctx.registerAction((sess, st, res) -> ServerUtils.redirectAll(server));
                    var card = UiBuilder.buttonTable()
                        .style("flatBordert")
                        .width(MenuContext.SAFE_CARD_WIDTH)
                        .margin(12f)
                        .clicked(redirectAct);

                    boolean isOnline = server.getMapName() != null;
                    String statusText = isOnline
                        ? Tr.t(ctx.getSession().locale, "hub.global.players", "players", server.getPlayers())
                        : "[scarlet]" + Tr.t(ctx.getSession().locale, "hub.global.server_offline");

                    card.add(UiBuilder.label("[gold]" + server.getName()).wrap().align("left").growX());
                    card.add(UiBuilder.label(statusText).align("right").padLeft(4f));
                    card.add(UiBuilder.label("[lightgray]" + Iconc.right).padLeft(6f));
                    card.row();

                    if (isOnline) {
                        String details = Tr.t(ctx.getSession().locale, "hub.global.gamemode", "mode", server.getMode())
                            + " | " + Tr.t(ctx.getSession().locale, "hub.global.map", "map", server.getMapName());
                        card.add(UiBuilder.label("[lightgray]" + details).wrap().align("left").growX().colspan(3));
                        card.row();
                    }

                    if (server.getDescription() != null && !server.getDescription().trim().isEmpty()) {
                        card.add(UiBuilder.label("[gray]" + server.getDescription()).wrap().align("left").growX().colspan(3));
                        card.row();
                    }

                    paneTable.add(card.growX().pad(2f)).row();
                });
            });

            if (page > 0 || (servers != null && servers.size() == size)) {
                ctx.row();
                ctx.table(bottom -> {
                    if (page > 0) {
                        String prevAct = ctx.registerAction((sess, st, res) -> Registry.get(GlobalServerListMenu.class).send(sess, page - 1));
                        bottom.add(UiBuilder.button(Tr.t(ctx.getSession().locale, "hub.list.previous")).clicked(prevAct).width(95f).height(36f).pad(2f));
                    }

                    if (servers != null && servers.size() == size) {
                        String nextAct = ctx.registerAction((sess, st, res) -> Registry.get(GlobalServerListMenu.class).send(sess, page + 1));
                        bottom.add(UiBuilder.button(Tr.t(ctx.getSession().locale, "hub.list.next")).clicked(nextAct).width(95f).height(36f).pad(2f));
                    }
                });
            }
        } catch (Exception e) {
            Log.err("Failed to build global server list menu", e);
            ctx.label("[scarlet]Failed to load server list");
        }
    }
}
