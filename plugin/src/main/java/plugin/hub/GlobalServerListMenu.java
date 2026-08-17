package plugin.hub;

import plugin.menus.PluginMenu;

import arc.func.Cons;
import arc.util.Log;
import mindustry.gen.Call;
import plugin.core.Registry;
import plugin.gateway.ApiGateway;
import plugin.utils.Tr;
import plugin.session.Session;
import dto.ServerDto;
import java.util.List;

public class GlobalServerListMenu extends PluginMenu<Integer> {

    private final Cons<ServerDto> handle;

    public GlobalServerListMenu(Cons<ServerDto> handle) {
        this.handle = handle;
    }

    @Override
    public void build(Session session, Integer page) {
        try {
            int size = 8;
            PaginationRequest request = new PaginationRequest().setPage(page).setSize(size);
            List<ServerDto> servers = Registry.get(ApiGateway.class).getServers(request);

            this.title = Tr.t(session.locale, "hub.global.title");
            this.description = "";

            text(Tr.t(session.locale, "hub.global.server_name"));
            text(Tr.t(session.locale, "hub.global.players_playing"));
            row();

            text(Tr.t(session.locale, "hub.global.server_gamemode"));
            text(Tr.t(session.locale, "hub.global.map_playing"));
            row();

            text(Tr.t(session.locale, "hub.global.server_mods"));
            row();

            text(Tr.t(session.locale, "hub.global.server_description"));
            row();

            servers.forEach(server -> {
                option("-----------------", (p, s) -> {
                });
                row();

                option(String.format("[#FFD700]%s", server.getName()),
                        (p, s) -> handle.get(server));
                option(Tr.t(session.locale, "hub.global.players", "players", server.getPlayers()),
                        (p, s) -> handle.get(server));
                row();

                option(Tr.t(session.locale, "hub.global.gamemode", "mode", server.getMode()),
                        (p, s) -> handle.get(server));
                option(Tr.t(session.locale, "hub.global.map", "map",
                        server.getMapName() != null ? server.getMapName()
                                : Tr.t(session.locale, "hub.global.server_offline")),
                        (p, s) -> handle.get(server));
                row();

                if (server.getMods() != null && !server.getMods().isEmpty()) {
                    option(Tr.t(session.locale, "hub.global.mods", "mods",
                            String.join(", ", server.getMods())),
                            (p, s) -> handle.get(server));
                    row();
                }

                if (server.getDescription() != null && !server.getDescription().trim().isEmpty()) {
                    option(String.format("[#B0B0B0]%s", server.getDescription()),
                            (p, s) -> handle.get(server));
                    row();
                }
            });

            if (page > 0) {
                option(Tr.t(session.locale, "hub.global.previous"),
                        (p, s) -> new GlobalServerListMenu(handle).send(p, s - 1));
            } else {
                option(Tr.t(session.locale, "hub.global.first_page"), (p, s) -> {
                    new GlobalServerListMenu(handle).send(p, s);
                    Call.infoToast(p.player.con, Tr.t(session.locale, "hub.global.dont_click"), 10f);
                });
            }

            if (servers.size() == size) {
                option(Tr.t(session.locale, "hub.global.next"), (p, s) -> new GlobalServerListMenu(handle).send(p, s + 1));
            } else {
                option(Tr.t(session.locale, "hub.global.no_more"), (p, s) -> {
                    new GlobalServerListMenu(handle).send(p, s);
                    Call.infoToast(p.player.con, Tr.t(session.locale, "hub.global.dont_click"), 10f);
                });
            }

            row();

            text(Tr.t(session.locale, "hub.global.close"));
        } catch (Exception e) {
            Log.err("Failed to build global server list menu", e);
        }
    }
}
