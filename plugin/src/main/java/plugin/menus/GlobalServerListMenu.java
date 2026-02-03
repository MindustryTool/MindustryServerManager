package plugin.menus;

import arc.util.Log;
import mindustry.gen.Call;
import plugin.commands.ClientCommandHandler;
import plugin.handler.ApiGateway;
import plugin.type.PaginationRequest;
import plugin.type.Session;
import dto.ServerDto;
import java.util.List;

public class GlobalServerListMenu extends PluginMenu<Integer> {
    @Override
    public void build(Session session, Integer page) {
        try {
            int size = 8;
            PaginationRequest request = new PaginationRequest().setPage(page).setSize(size);
            List<ServerDto> servers = ApiGateway.getServers(request);

            this.title = ApiGateway.translate(session.locale, "@Servers");
            this.description = "";

            text(ApiGateway.translate(session.locale, "[#FFD700]", "@Server name"));
            text(ApiGateway.translate(session.locale, "[#FFD700]", "@Players playing"));
            row();

            text(ApiGateway.translate(session.locale, "[#87CEEB]", "@Server Gamemode"));
            text(ApiGateway.translate(session.locale, "[#FFA500]", "@Map Playing"));
            row();

            text(ApiGateway.translate(session.locale, "[#DA70D6]", "@Server Mods"));
            row();

            text(ApiGateway.translate(session.locale, "[#B0B0B0]", "@Server Description"));
            row();

            servers.forEach(server -> {
                option("-----------------", (p, s) -> {
                });
                row();

                option(String.format("[#FFD700]%s", server.getName()),
                        (p, s) -> ClientCommandHandler.onServerChoose(p, server.getId().toString(), server.getName()));
                option(ApiGateway.translate(session.locale, "[#32CD32]", "@Players: ", server.getPlayers()),
                        (p, s) -> ClientCommandHandler.onServerChoose(p, server.getId().toString(), server.getName()));
                row();

                option(ApiGateway.translate(session.locale, "[#87CEEB]", "@Gamemode: ", server.getMode()),
                        (p, s) -> ClientCommandHandler.onServerChoose(p, server.getId().toString(), server.getName()));
                option(ApiGateway.translate(session.locale, "[#1E90FF]", "@Map: ",
                        server.getMapName() != null ? server.getMapName()
                                : ApiGateway.translate(session.locale, "[#FF4500]",
                                        "@Server offline")),
                        (p, s) -> ClientCommandHandler.onServerChoose(p, server.getId().toString(), server.getName()));
                row();

                if (server.getMods() != null && !server.getMods().isEmpty()) {
                    option(ApiGateway.translate(session.locale, "[#DA70D6]", "@Mods: ",
                            String.join(", ", server.getMods())), (p,
                            s) -> ClientCommandHandler.onServerChoose(p, server.getId().toString(), server.getName()));
                    row();
                }

                if (server.getDescription() != null && !server.getDescription().trim().isEmpty()) {
                    option(String.format("[#B0B0B0]%s", server.getDescription()), (p, s) -> ClientCommandHandler
                            .onServerChoose(p, server.getId().toString(), server.getName()));
                    row();
                }
            });

            if (page > 0) {
                option(ApiGateway.translate(session.locale, "[yellow]", "@Previous"), (p, s) -> new GlobalServerListMenu().send(p, s - 1));
            } else {
                option(ApiGateway.translate(session.locale, "@First page"), (p, s) -> {
                    new GlobalServerListMenu().send(p, s);
                    Call.infoToast(p.player.con, ApiGateway.translate(session.locale, "@Please don't click there"), 10f);
                });
            }

            if (servers.size() == size) {
                option(ApiGateway.translate(session.locale, "[green]", "@Next"), (p, s) -> new GlobalServerListMenu().send(p, s + 1));
            } else {
                option(ApiGateway.translate(session.locale, "@No more"), (p, s) -> {
                    new GlobalServerListMenu().send(p, s);
                    Call.infoToast(p.player.con, ApiGateway.translate(session.locale, "@Please don't click there"), 10f);
                });
            }

            row();

            text(ApiGateway.translate(session.locale, "[red]", "@Close"));
        } catch (Exception e) {
            Log.err("Failed to build global server list menu", e);
        }
    }
}
