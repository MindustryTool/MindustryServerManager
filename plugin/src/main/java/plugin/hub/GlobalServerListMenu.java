package plugin.hub;

import java.util.Comparator;
import java.util.List;
import arc.util.Log;
import arc.util.Scaling;
import common.server.Server;
import mindustry.gen.Iconc;
import mindustry.ui.builder.UiBuilder;
import mindustry.ui.builder.UiBuilder.PaneBuilder;
import mindustry.ui.builder.UiBuilder.TableBuilder;
import plugin.annotations.Component;
import plugin.core.Registry;
import plugin.gateway.ApiGateway;
import plugin.menus.MenuContext;
import plugin.menus.ServerMenu;
import plugin.utils.Tr;

@Component
public class GlobalServerListMenu extends ServerMenu<Integer> {

    private final ApiGateway apiGateway;
    private final ServerImageService serverImageService;

    public GlobalServerListMenu(ApiGateway apiGateway, ServerImageService serverImageService) {
        this.apiGateway = apiGateway;
        this.serverImageService = serverImageService;
        this.fillScreen = true;
    }

    @Override
    public void build(MenuContext<Integer> ctx) {
        int page = ctx.getState() != null ? ctx.getState() : 0;
        int size = 12;

        this.title = Tr.t(ctx.getSession().locale, "hub.global.title");

        try {
            PaginationRequest request = new PaginationRequest().setPage(page).setSize(size);
            List<Server> servers = apiGateway.getServers(request);

            if (ctx.getSession() != null && ctx.getSession().player != null && servers != null) {
                serverImageService.streamServerTextures(ctx.getSession().player, servers);
            }

            ctx.label(Tr.t(ctx.getSession().locale, "hub.choose_server")).padBottom(4f);
            ctx.row();

            TableBuilder grid = UiBuilder.table().align("top").align("left");

            if (servers == null || servers.isEmpty()) {
                grid.add(UiBuilder.label("[lightgray]" + Tr.t(ctx.getSession().locale, "hub.global.server_offline"))
                        .wrap()
                        .align("left")
                        .growX()
                        .pad(16f));
            } else {
                int cols = ctx.isMobile() ? 1 : 2;
                int[] index = {0};

                servers.stream().sorted(Comparator.comparing(Server::getPlayers).reversed()).forEach(server -> {
                    String serverId = server.getId() != null ? server.getId().toString() : "";
                    boolean isOnline = server.getMapName() != null;
                    String statusText = isOnline
                            ? "[green]● " + Tr.t(ctx.getSession().locale, "hub.global.players", "players", server.getPlayers())
                            : "[scarlet]● " + Tr.t(ctx.getSession().locale, "hub.global.server_offline");

                    TableBuilder card = UiBuilder.table().background("pane").margin(10f).align("top").align("left");
                    card.width(200f).pad(4f);

                    card.add(UiBuilder.image("net-server-" + serverId)
                            .placeholder("nomap")
                            .scaling(Scaling.fit)
                            .width(180f)
                            .height(100f)
                            .align("center"))
                        .row();

                    card.add(UiBuilder.label("[gold]" + server.getName())
                            .wrap()
                            .align("left")
                            .labelAlign("left")
                            .width(180f)
                            .padTop(4f))
                        .row();

                    card.add(UiBuilder.label(statusText)
                            .align("left")
                            .labelAlign("left")
                            .width(180f)
                            .padTop(2f))
                        .row();

                    if (isOnline) {
                        String details = Tr.t(ctx.getSession().locale, "hub.global.gamemode", "mode", server.getMode())
                                + " • " + Tr.t(ctx.getSession().locale, "hub.global.map", "map", server.getMapName());
                        card.add(UiBuilder.label("[lightgray]" + details)
                                .wrap()
                                .align("left")
                                .labelAlign("left")
                                .width(180f)
                                .padTop(2f))
                            .row();
                    }

                    if (server.getDescription() != null && !server.getDescription().trim().isEmpty()) {
                        card.add(UiBuilder.label("[gray]" + server.getDescription())
                                .wrap()
                                .align("left")
                                .labelAlign("left")
                                .width(180f)
                                .padTop(2f))
                            .row();
                    }

                    String redirectAct = ctx.registerAction((sess, st, res) -> ServerUtils.redirectAll(server));
                    String buttonLabel = "[gold]" + Iconc.players + " Redirect All";
                    card.add(UiBuilder.button(buttonLabel)
                            .clicked(redirectAct)
                            .width(180f)
                            .height(34f)
                            .align("left")
                            .padTop(6f));

                    grid.add(card);
                    if (++index[0] % cols == 0) {
                        grid.row();
                    }
                });
            }

            PaneBuilder pane = UiBuilder.pane().add(grid).grow();
            ctx.add(pane);

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
            ctx.label(Tr.t(ctx.getSession(), "hub.global.load_failed"));
        }
    }
}
