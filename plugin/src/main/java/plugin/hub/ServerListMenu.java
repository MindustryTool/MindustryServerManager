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
public class ServerListMenu extends ServerMenu<Integer> {

    private final ApiGateway apiGateway;

    @Override
    public void build(MenuContext<Integer> ctx) {
        int page = ctx.getState() != null ? ctx.getState() : 0;
        int size = 6;

        this.title = Tr.t(ctx.getSession().locale, "hub.list.title");

        try {
            PaginationRequest request = new PaginationRequest().setPage(page).setSize(size);
            List<Server> servers = apiGateway.getServers(request);

            ctx.label(Tr.t(ctx.getSession().locale, "hub.choose_server"));
            ctx.row();

            ctx.pane(MenuContext.SAFE_WIDTH, 320f, paneTable -> {
                if (servers == null || servers.isEmpty()) {
                    paneTable.add(UiBuilder.label("[lightgray]" + Tr.t(ctx.getSession().locale, "hub.list.server_offline")).pad(16f));
                    return;
                }

                servers.stream().sorted(Comparator.comparing(Server::getPlayers).reversed()).forEach(server -> {
                    TableBuilder card = UiBuilder.table().pad(6f);

                    var mods = server.getMods();
                    if (mods != null) {
                        mods.removeIf(m -> m.trim().equalsIgnoreCase("Controller"));
                    }

                    boolean isOnline = server.getMapName() != null;
                    String statusText = isOnline
                        ? Tr.t(ctx.getSession().locale, "hub.list.players", "players", server.getPlayers())
                        : "[scarlet]" + Tr.t(ctx.getSession().locale, "hub.list.server_offline");

                    card.add(UiBuilder.label("[yellow]" + server.getName()).align("left"));
                    card.add(UiBuilder.label(statusText).align("right"));
                    card.row();

                    if (isOnline) {
                        String details = Tr.t(ctx.getSession().locale, "hub.list.gamemode", "mode", server.getMode().toLowerCase())
                            + " | " + Tr.t(ctx.getSession().locale, "hub.list.map", "map", server.getMapName());
                        card.add(UiBuilder.label("[lightgray]" + details).align("left").colspan(2));
                        card.row();
                    }

                    if (server.getDescription() != null && !server.getDescription().trim().isEmpty()) {
                        card.add(UiBuilder.label("[gray]" + server.getDescription()).wrap().align("left").colspan(2));
                        card.row();
                    }

                    String connectAction = ctx.registerAction((sess, st, res) -> ServerUtils.redirect(sess.player, server));
                    card.add(UiBuilder.button(Tr.t(ctx.getSession().locale, "hub.redirect.yes")).clicked(connectAction).width(140f).height(40f).align("right").colspan(2).pad(4f));

                    paneTable.add(card.growX().pad(4f)).row();
                });
            });

            ctx.row();

            ctx.table(bottom -> {
                if (page > 0) {
                    String prevAct = ctx.registerAction((sess, st, res) -> Registry.get(ServerListMenu.class).send(sess, page - 1));
                    bottom.add(UiBuilder.button(Tr.t(ctx.getSession().locale, "hub.list.previous")).clicked(prevAct).width(95f).height(40f).pad(4f));
                }

                if (servers != null && servers.size() == size) {
                    String nextAct = ctx.registerAction((sess, st, res) -> Registry.get(ServerListMenu.class).send(sess, page + 1));
                    bottom.add(UiBuilder.button(Tr.t(ctx.getSession().locale, "hub.list.next")).clicked(nextAct).width(95f).height(40f).pad(4f));
                }

                String closeAct = ctx.registerAction((sess, st, res) -> {});
                bottom.add(UiBuilder.button(Tr.t(ctx.getSession().locale, "hub.list.close")).clicked(closeAct).width(95f).height(40f).pad(4f));
            });
        } catch (Exception e) {
            Log.err("Failed to build server list menu", e);
            ctx.label("[scarlet]Failed to load server list");
        }
    }
}
