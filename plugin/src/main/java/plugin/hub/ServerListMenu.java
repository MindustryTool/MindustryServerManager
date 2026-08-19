package plugin.hub;

import plugin.menus.PluginMenu;

import arc.util.Log;
import plugin.core.Registry;
import plugin.gateway.ApiGateway;
import plugin.utils.Tr;
import plugin.session.Session;
import dto.ServerDto;

import java.util.Comparator;
import java.util.List;

public class ServerListMenu extends PluginMenu<Integer> {

    public ServerListMenu() {
    }

    @Override
    public void build(Session session, Integer page) {
        try {
            int size = 4;

            PaginationRequest request = new PaginationRequest().setPage(page).setSize(size);
            List<ServerDto> servers = Registry.get(ApiGateway.class).getServers(request);

            this.title = Tr.t(session.locale, "hub.list.title");
            this.description = Tr.t(session.locale, "hub.choose_server");

            servers.stream().sorted(Comparator.comparing(ServerDto::getPlayers).reversed()).forEach(server -> {
                row();
                var mods = server.getMods();

                if (mods != null) {
                    mods.removeIf(m -> m.trim().equalsIgnoreCase("Controller"));
                }

                if (server.getMapName() == null) {
                    option(String.format("[yellow]%s", server.getName()),
                            (p, s) -> ServerUtils.redirect(p.player, server));
                    option(Tr.t(session.locale, "hub.list.server_offline"),
                            (p, s) -> ServerUtils.redirect(p.player, server));
                } else {
                    option(server.getName(),
                            (p, s) -> ServerUtils.redirect(p.player, server));
                    option(Tr.t(session.locale, "hub.list.players", "players", server.getPlayers()),
                            (p, s) -> ServerUtils.redirect(p.player, server));

                    row();
                    option(Tr.t(session.locale, "hub.list.gamemode", "mode",
                            server.getMode().toLowerCase()),
                            (p, s) -> ServerUtils.redirect(p.player, server));
                    option(Tr.t(session.locale, "hub.list.map", "map", server.getMapName()),
                            (p, s) -> ServerUtils.redirect(p.player, server));
                }

                if (server.getMods() != null && !server.getMods().isEmpty()) {
                    row();
                    option(Tr.t(session.locale, "hub.list.mods", "mods", String.join(", ", mods)),
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
                option(Tr.t(session.locale, "hub.list.previous"),
                        (p, s) -> new ServerListMenu().send(p, s - 1));
            }

            if (servers.size() == size) {
                option(Tr.t(session.locale, "hub.list.next"),
                        (p, s) -> new ServerListMenu().send(p, s + 1));
            }

            row();
            text(Tr.t(session.locale, "hub.list.close"));
        } catch (Exception e) {
            Log.err("Failed to build server list menu", e);
        }
    }
}
