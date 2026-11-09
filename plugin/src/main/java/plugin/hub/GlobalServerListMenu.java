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

    public static final float CARD_WIDTH = 330f;
    public static final float CARD_HEIGHT = 370f;

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

            ctx.getRoot().align("top");

            float cardWidth = CARD_WIDTH;
            float cardHeight = CARD_HEIGHT;
            float contentWidth = cardWidth - 20f;
            float spacing = 8f;
            int maxCols = ctx.getResponsiveColumns(cardWidth, spacing);
            float innerWidth = maxCols * cardWidth + Math.max(0, maxCols - 1) * spacing;
            int[] index = { 0 };

            TableBuilder grid = UiBuilder.table().align("topLeft").width(innerWidth);

            if (servers == null || servers.isEmpty()) {
                grid.add(UiBuilder.label("[lightgray]" + Tr.t(ctx.getSession().locale, "hub.global.server_offline"))
                        .wrap()
                        .align("center")
                        .growX()
                        .pad(16f));
            } else {
                servers.stream().sorted(Comparator.comparing(Server::getPlayers).reversed()).forEach(server -> {
                    String serverId = server.getId() != null ? server.getId().toString() : "";
                    boolean isOnline = server.getMapName() != null;
                    String statusText = isOnline
                            ? "[green]● " + Tr.t(ctx.getSession().locale, "hub.global.players", "players",
                                    server.getPlayers())
                            : "[scarlet]● " + Tr.t(ctx.getSession().locale, "hub.global.server_offline");

                    TableBuilder card = UiBuilder.table()
                            .background("pane")
                            .margin(10f)
                            .align("topLeft")
                            .width(cardWidth)
                            .height(cardHeight)
                            .pad(4f);

                    card.add(UiBuilder.image("net-server-" + serverId)
                            .placeholder("nomap")
                            .scaling(Scaling.fit)
                            .width(contentWidth)
                            .height(180f)
                            .align("center"))
                            .row();

                    card.add(UiBuilder.label("[gold]" + server.getName())
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
                            .padTop(3f))
                            .row();

                    if (isOnline) {
                        String details = Tr.t(ctx.getSession().locale, "hub.global.gamemode", "mode", server.getMode())
                                + " • " + Tr.t(ctx.getSession().locale, "hub.global.map", "map", server.getMapName());
                        card.add(UiBuilder.label("[lightgray]" + details)
                                .wrap()
                                .align("left")
                                .labelAlign("left")
                                .width(contentWidth)
                                .padTop(3f))
                                .row();
                    }

                    if (server.getDescription() != null && !server.getDescription().trim().isEmpty()) {
                        card.add(UiBuilder.label("[gray]" + server.getDescription())
                                .wrap()
                                .align("left")
                                .labelAlign("left")
                                .width(contentWidth)
                                .padTop(3f))
                                .row();
                    }

                    card.add(UiBuilder.space().growY()).row();

                    String redirectAct = ctx.registerAction((sess, st, res) -> ServerUtils.redirectAll(server));
                    String buttonLabel = "[gold]" + Iconc.players + " Redirect All";
                    card.add(UiBuilder.button(buttonLabel)
                            .clicked(redirectAct)
                            .width(contentWidth)
                            .height(38f)
                            .align("left")
                            .padTop(8f));

                    grid.add(card);
                    if (++index[0] % maxCols == 0) {
                        grid.row();
                    }
                });
            }

            TableBuilder contentWrapper = UiBuilder.table().align("top");
            contentWrapper.add(grid);

            PaneBuilder pane = UiBuilder.pane().add(contentWrapper).grow().align("top");
            ctx.add(pane);

            if (page > 0 || (servers != null && servers.size() == size)) {
                ctx.row();
                ctx.table(bottom -> {
                    if (page > 0) {
                        String prevAct = ctx.registerAction(
                                (sess, st, res) -> Registry.get(GlobalServerListMenu.class).send(sess, page - 1));
                        bottom.add(UiBuilder.button(Tr.t(ctx.getSession().locale, "hub.list.previous")).clicked(prevAct)
                                .width(95f).height(36f).pad(2f));
                    }

                    if (servers != null && servers.size() == size) {
                        String nextAct = ctx.registerAction(
                                (sess, st, res) -> Registry.get(GlobalServerListMenu.class).send(sess, page + 1));
                        bottom.add(UiBuilder.button(Tr.t(ctx.getSession().locale, "hub.list.next")).clicked(nextAct)
                                .width(95f).height(36f).pad(2f));
                    }
                });
            }
        } catch (Exception e) {
            Log.err("Failed to build global server list menu", e);
            ctx.label(Tr.t(ctx.getSession(), "hub.global.load_failed"));
        }
    }
}
