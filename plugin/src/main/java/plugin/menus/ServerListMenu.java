package plugin.menus;

import arc.util.Log;
import plugin.Cfg;
import plugin.core.Registry;
import plugin.service.ApiGateway;
import plugin.service.I18n;
import plugin.type.PaginationRequest;
import plugin.type.Session;
import plugin.utils.ServerUtils;
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

            this.title = I18n.t(session.locale, "@List of all servers");
            this.description = I18n.t(session.locale, "@" + Cfg.CHOOSE_SERVER_MESSAGE);

            servers.stream().sorted(Comparator.comparing(ServerDto::getPlayers).reversed()).forEach(server -> {
                row();
                var mods = server.getMods();

                if (mods != null) {
                    mods.removeIf(m -> m.trim().equalsIgnoreCase("Controller"));
                }

                if (server.getMapName() == null) {
                    option(String.format("[yellow]%s", server.getName()),
                            (p, s) -> ServerUtils.redirect(p.player, server));
                    option(I18n.t(session.locale, "[scarlet]", "@Server offline."),
                            (p, s) -> ServerUtils.redirect(p.player, server));
                } else {
                    option(server.getName(),
                            (p, s) -> ServerUtils.redirect(p.player, server));
                    option(I18n.t(session.locale, "[lime]", "@Players:[] ", server.getPlayers()),
                            (p, s) -> ServerUtils.redirect(p.player, server));

                    row();
                    option(I18n.t(session.locale, "[cyan]", "@Gamemode:[] ",
                            server.getMode().toLowerCase()),
                            (p, s) -> ServerUtils.redirect(p.player, server));
                    option(I18n.t(session.locale, "[blue]", "@Map:[] ", server.getMapName()),
                            (p, s) -> ServerUtils.redirect(p.player, server));
                }

                if (server.getMods() != null && !server.getMods().isEmpty()) {
                    row();
                    option(I18n.t(session.locale, "[purple]", "@Mods:[] ", String.join(", ", mods)),
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
                option(I18n.t(session.locale, "[orange]", "@Previous"),
                        (p, s) -> new ServerListMenu().send(p, s - 1));
            }

            if (servers.size() == size) {
                option(I18n.t(session.locale, "[lime]", "@Next"),
                        (p, s) -> new ServerListMenu().send(p, s + 1));
            }

            row();
            text(I18n.t(session.locale, "[scarlet]", "@Close"));
        } catch (Throwable e) {
            Log.err("Failed to build server list menu", e);
        }
    }
}
