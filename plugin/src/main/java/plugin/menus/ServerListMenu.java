package plugin.menus;

import arc.util.Log;
import mindustry.gen.Call;
import plugin.Config;
import plugin.handler.ApiGateway;
import plugin.type.PaginationRequest;
import plugin.type.Session;
import plugin.utils.ServerUtils;
import dto.ServerDto;

import java.util.Comparator;
import java.util.List;

public class ServerListMenu extends PluginMenu<Integer> {
    @Override
    public void build(Session session, Integer page) {
        try {
            int size = 5;

            PaginationRequest request = new PaginationRequest().setPage(page).setSize(size);
            List<ServerDto> servers = ApiGateway.getServers(request);

            this.title = ApiGateway.translate(session.locale, "@List of all servers");
            this.description = ApiGateway.translate(session.locale, "@" + Config.CHOOSE_SERVER_MESSAGE);

            servers.stream().sorted(Comparator.comparing(ServerDto::getPlayers).reversed()).forEach(server -> {
                row();
                var mods = server.getMods();

                if (mods != null) {
                    mods.removeIf(m -> m.trim().equalsIgnoreCase("mindustrytoolplugin"));
                }

                if (server.getMapName() == null) {
                    option(String.format("[yellow]%s", server.getName()),
                            (p, s) -> ServerUtils.redirect(p.player, server));
                    option(ApiGateway.translate(session.locale, "[scarlet]", "@Server offline."),
                            (p, s) -> ServerUtils.redirect(p.player, server));
                } else {
                    option(server.getName(),
                            (p, s) -> ServerUtils.redirect(p.player, server));
                    option(ApiGateway.translate(session.locale, "[lime]", "@Players:[] ", server.getPlayers()),
                            (p, s) -> ServerUtils.redirect(p.player, server));

                    row();
                    option(ApiGateway.translate(session.locale, "[cyan]", "@Gamemode:[] ",
                            server.getMode().toLowerCase()),
                            (p, s) -> ServerUtils.redirect(p.player, server));
                    option(ApiGateway.translate(session.locale, "[blue]", "@Map:[] ", server.getMapName()),
                            (p, s) -> ServerUtils.redirect(p.player, server));
                }

                if (server.getMods() != null && !server.getMods().isEmpty()) {
                    row();
                    option(ApiGateway.translate(session.locale, "[purple]", "@Mods:[] ", String.join(", ", mods)),
                            (p, s) -> ServerUtils.redirect(p.player, server));
                }

                if (server.getDescription() != null && !server.getDescription().trim().isEmpty()) {
                    row();
                    option(String.format("[grey]%s", server.getDescription()),
                            (p, s) -> ServerUtils.redirect(p.player, server));
                }

                row();
                text("");
            });

            row();
            if (page > 0) {
                option(ApiGateway.translate(session.locale, "[orange]", "@Previous"),
                        (p, s) -> new ServerListMenu().send(p, s - 1));
            } else {
                option(ApiGateway.translate(session.locale, "@First page"), (p, s) -> {
                    new ServerListMenu().send(p, s);
                    Call.infoToast(p.player.con, ApiGateway.translate(session.locale, "@Please don't click there"),
                            10f);
                });
            }

            if (servers.size() == size) {
                option(ApiGateway.translate(session.locale, "[lime]", "@Next"),
                        (p, s) -> new ServerListMenu().send(p, s + 1));
            } else {
                option(ApiGateway.translate(session.locale, "@No more"), (p, s) -> {
                    new ServerListMenu().send(p, s);
                    Call.infoToast(p.player.con, ApiGateway.translate(session.locale, "@Please don't click there"),
                            10f);
                });
            }

            row();
            text(ApiGateway.translate(session.locale, "[scarlet]", "@Close"));
        } catch (Throwable e) {
            Log.err("Failed to build server list menu", e);
        }
    }
}
