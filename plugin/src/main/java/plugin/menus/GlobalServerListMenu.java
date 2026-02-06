package plugin.menus;

import arc.util.Log;
import mindustry.gen.Call;
import plugin.Registry;
import plugin.commands.ClientCommandHandler;
import plugin.service.ApiGateway;
import plugin.service.I18n;
import plugin.type.PaginationRequest;
import plugin.type.Session;
import dto.ServerDto;
import java.util.List;

public class GlobalServerListMenu extends PluginMenu<Integer> {

    public GlobalServerListMenu() {
    }

    @Override
    public void build(Session session, Integer page) {
        try {
            int size = 8;
            PaginationRequest request = new PaginationRequest().setPage(page).setSize(size);
            List<ServerDto> servers = Registry.get(ApiGateway.class).getServers(request);

            this.title = I18n.t(session.locale, "@Servers");
            this.description = "";

            text(I18n.t(session.locale, "[#FFD700]", "@Server name"));
            text(I18n.t(session.locale, "[#FFD700]", "@Players playing"));
            row();

            text(I18n.t(session.locale, "[#87CEEB]", "@Server Gamemode"));
            text(I18n.t(session.locale, "[#FFA500]", "@Map Playing"));
            row();

            text(I18n.t(session.locale, "[#DA70D6]", "@Server Mods"));
            row();

            text(I18n.t(session.locale, "[#B0B0B0]", "@Server Description"));
            row();

            var clientCommandHandler = Registry.get(ClientCommandHandler.class);

            servers.forEach(server -> {
                option("-----------------", (p, s) -> {
                });
                row();

                option(String.format("[#FFD700]%s", server.getName()),
                        (p, s) -> clientCommandHandler.onServerChoose(p, server.getId().toString(), server.getName()));
                option(I18n.t(session.locale, "[#32CD32]", "@Players: ", server.getPlayers()),
                        (p, s) -> clientCommandHandler.onServerChoose(p, server.getId().toString(), server.getName()));
                row();

                option(I18n.t(session.locale, "[#87CEEB]", "@Gamemode: ", server.getMode()),
                        (p, s) -> clientCommandHandler.onServerChoose(p, server.getId().toString(), server.getName()));
                option(I18n.t(session.locale, "[#1E90FF]", "@Map: ",
                        server.getMapName() != null ? server.getMapName()
                                : I18n.t(session.locale, "[#FF4500]",
                                        "@Server offline")),
                        (p, s) -> clientCommandHandler.onServerChoose(p, server.getId().toString(), server.getName()));
                row();

                if (server.getMods() != null && !server.getMods().isEmpty()) {
                    option(I18n.t(session.locale, "[#DA70D6]", "@Mods: ",
                            String.join(", ", server.getMods())),
                            (p,
                                    s) -> clientCommandHandler.onServerChoose(p, server.getId().toString(),
                                            server.getName()));
                    row();
                }

                if (server.getDescription() != null && !server.getDescription().trim().isEmpty()) {
                    option(String.format("[#B0B0B0]%s", server.getDescription()), (p, s) -> clientCommandHandler
                            .onServerChoose(p, server.getId().toString(), server.getName()));
                    row();
                }
            });

            if (page > 0) {
                option(I18n.t(session.locale, "[yellow]", "@Previous"),
                        (p, s) -> new GlobalServerListMenu().send(p, s - 1));
            } else {
                option(I18n.t(session.locale, "@First page"), (p, s) -> {
                    new GlobalServerListMenu().send(p, s);
                    Call.infoToast(p.player.con, I18n.t(session.locale, "@Please don't click there"), 10f);
                });
            }

            if (servers.size() == size) {
                option(I18n.t(session.locale, "[green]", "@Next"), (p, s) -> new GlobalServerListMenu().send(p, s + 1));
            } else {
                option(I18n.t(session.locale, "@No more"), (p, s) -> {
                    new GlobalServerListMenu().send(p, s);
                    Call.infoToast(p.player.con, I18n.t(session.locale, "@Please don't click there"), 10f);
                });
            }

            row();

            text(I18n.t(session.locale, "[red]", "@Close"));
        } catch (Exception e) {
            Log.err("Failed to build global server list menu", e);
        }
    }
}
