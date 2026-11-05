package plugin.hub;

import java.util.Comparator;
import java.util.List;
import arc.util.Log;
import common.server.Server;
import lombok.RequiredArgsConstructor;
import mindustry.ui.builder.UiBuilder;
import mindustry.ui.builder.UiBuilder.TableBuilder;
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

            ctx.label(Tr.t(ctx.getSession().locale, "hub.choose_server"));
            ctx.row();

            ctx.pane(MenuContext.SAFE_WIDTH, 320f, paneTable -> {
                if (servers == null || servers.isEmpty()) {
                    paneTable.add(UiBuilder.label("[lightgray]" + Tr.t(ctx.getSession().locale, "hub.global.server_offline")).pad(16f));
                    return;
                }

                servers.stream().sorted(Comparator.comparing(Server::getPlayers).reversed()).forEach(server -> {
                    TableBuilder card = UiBuilder.table().pad(6f);

                    boolean isOnline = server.getMapName() != null;
                    String statusText = isOnline
                        ? Tr.t(ctx.getSession().locale, "hub.global.players", "players", server.getPlayers())
                        : "[scarlet]" + Tr.t(ctx.getSession().locale, "hub.global.server_offline");

                    card.add(UiBuilder.label("[gold]" + server.getName()).align("left"));
                    card.add(UiBuilder.label(statusText).align("right"));
                    card.row();

                    if (isOnline) {
                        String details = Tr.t(ctx.getSession().locale, "hub.global.gamemode", "mode", server.getMode())
                            + " | " + Tr.t(ctx.getSession().locale, "hub.global.map", "map", server.getMapName());
                        card.add(UiBuilder.label("[lightgray]" + details).align("left").colspan(2));
                        card.row();
                    }

                    if (server.getDescription() != null && !server.getDescription().trim().isEmpty()) {
                        card.add(UiBuilder.label("[gray]" + server.getDescription()).wrap().align("left").colspan(2));
                        card.row();
                    }

                    String redirectAct = ctx.registerAction((sess, st, res) -> ServerUtils.redirectAll(server));
                    card.add(UiBuilder.button("Redirect All").clicked(redirectAct).width(140f).height(40f).align("right").colspan(2).pad(4f));

                    paneTable.add(card.growX().pad(4f)).row();
                });
            });

            ctx.row();

            ctx.table(bottom -> {
                if (page > 0) {
                    String prevAct = ctx.registerAction((sess, st, res) -> Registry.get(GlobalServerListMenu.class).send(sess, page - 1));
                    bottom.add(UiBuilder.button(Tr.t(ctx.getSession().locale, "hub.list.previous")).clicked(prevAct).width(95f).height(40f).pad(4f));
                }

                if (servers != null && servers.size() == size) {
                    String nextAct = ctx.registerAction((sess, st, res) -> Registry.get(GlobalServerListMenu.class).send(sess, page + 1));
                    bottom.add(UiBuilder.button(Tr.t(ctx.getSession().locale, "hub.list.next")).clicked(nextAct).width(95f).height(40f).pad(4f));
                }

                String closeAct = ctx.registerAction((sess, st, res) -> {});
                bottom.add(UiBuilder.button(Tr.t(ctx.getSession().locale, "hub.list.close")).clicked(closeAct).width(95f).height(40f).pad(4f));
            });
        } catch (Exception e) {
            Log.err("Failed to build global server list menu", e);
            ctx.label("[scarlet]Failed to load server list");
        }
    }
}
