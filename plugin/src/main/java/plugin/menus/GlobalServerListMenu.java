package plugin.menus;

import arc.util.Log;
import mindustry.gen.Call;
import mindustry.gen.Player;
import plugin.handler.ApiGateway;
import plugin.handler.ClientCommandHandler;
import plugin.type.PaginationRequest;
import dto.ServerDto;
import java.util.List;

public class GlobalServerListMenu extends PluginMenu<Integer> {
    @Override
    public void build(Player player, Integer page) {
        try {
            int size = 8;
            PaginationRequest request = new PaginationRequest().setPage(page).setSize(size);
            List<ServerDto> servers = ApiGateway.getServers(request);

            this.title = "Servers";
            this.description = "";

            text("[#FFD700]Server name");
            text("[#FFD700]Players playing");
            row();

            text("[#87CEEB]Server Gamemode");
            text("[#FFA500]Map Playing");
            row();

            text("[#DA70D6]Server Mods");
            row();

            text("[#B0B0B0]Server Description");
            row();

            servers.forEach(server -> {
                option("-----------------", (p, s) -> {
                });
                row();

                option(String.format("[#FFD700]%s", server.getName()),
                        (p, s) -> ClientCommandHandler.onServerChoose(p, server.getId().toString(), server.getName()));
                option(String.format("[#32CD32]Players: %d", server.getPlayers()),
                        (p, s) -> ClientCommandHandler.onServerChoose(p, server.getId().toString(), server.getName()));
                row();

                option(String.format("[#87CEEB]Gamemode: %s", server.getMode()),
                        (p, s) -> ClientCommandHandler.onServerChoose(p, server.getId().toString(), server.getName()));
                option(String.format("[#1E90FF]Map: %s",
                        server.getMapName() != null ? server.getMapName() : "[#FF4500]Server offline"),
                        (p, s) -> ClientCommandHandler.onServerChoose(p, server.getId().toString(), server.getName()));
                row();

                if (server.getMods() != null && !server.getMods().isEmpty()) {
                    option(String.format("[#DA70D6]Mods: %s", String.join(", ", server.getMods())), (p,
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
                option("[yellow]Previous", (p, s) -> new GlobalServerListMenu().send(p, s - 1));
            } else {
                option("First page", (p, s) -> {
                    new GlobalServerListMenu().send(p, s);
                    Call.infoToast(p.con, "Please don't click there", 10f);
                });
            }

            if (servers.size() == size) {
                option("[green]Next", (p, s) -> new GlobalServerListMenu().send(p, s + 1));
            } else {
                option("No more", (p, s) -> {
                    new GlobalServerListMenu().send(p, s);
                    Call.infoToast(p.con, "Please don't click there", 10f);
                });
            }

            row();

            text("[red]Close");
        } catch (Exception e) {
            Log.err("Failed to build global server list menu", e);
        }
    }
}
