package plugin.menus;

import arc.util.Log;
import mindustry.gen.Call;
import mindustry.gen.Player;
import plugin.Config;
import plugin.handler.ApiGateway;
import plugin.type.PaginationRequest;
import plugin.utils.ServerUtils;
import dto.ServerDto;

import java.util.Comparator;
import java.util.List;

public class ServerListMenu extends PluginMenu<Integer> {
    @Override
    public void build(Player player, Integer page) {
        try {
            int size = 5;

            PaginationRequest request = new PaginationRequest().setPage(page).setSize(size);
            List<ServerDto> servers = ApiGateway.getServers(request);

            this.title = "List of all servers";
            this.description = Config.CHOOSE_SERVER_MESSAGE;

            servers.stream().sorted(Comparator.comparing(ServerDto::getPlayers).reversed()).forEach(server -> {
                row();
                var mods = server.getMods();

                if (mods != null) {
                    mods.removeIf(m -> m.trim().equalsIgnoreCase("mindustrytoolplugin"));
                }

                if (server.getMapName() == null) {
                    option(String.format("[yellow]%s", server.getName()),
                            (p, s) -> ServerUtils.redirect(p, server));
                    option("[scarlet]Server offline.",
                            (p, s) -> ServerUtils.redirect(p, server));
                } else {
                    option(server.getName(),
                            (p, s) -> ServerUtils.redirect(p, server));
                    option(String.format("[lime]Players:[] %d", server.getPlayers()),
                            (p, s) -> ServerUtils.redirect(p, server));

                    row();
                    option(String.format("[cyan]Gamemode:[] %s", server.getMode().toLowerCase()),
                            (p, s) -> ServerUtils.redirect(p, server));
                    option(String.format("[blue]Map:[] %s", server.getMapName()),
                            (p, s) -> ServerUtils.redirect(p, server));
                }

                if (server.getMods() != null && !server.getMods().isEmpty()) {
                    row();
                    option(String.format("[purple]Mods:[] %s", String.join(", ", mods)),
                            (p, s) -> ServerUtils.redirect(p, server));
                }

                if (server.getDescription() != null && !server.getDescription().trim().isEmpty()) {
                    row();
                    option(String.format("[grey]%s", server.getDescription()),
                            (p, s) -> ServerUtils.redirect(p, server));
                }

                row();
                text("");
            });

            row();
            if (page > 0) {
                option("[orange]Previous", (p, s) -> new ServerListMenu().send(p, s - 1));
            } else {
                option("First page", (p, s) -> {
                    new ServerListMenu().send(p, s);
                    Call.infoToast(p.con, "Please don't click there", 10f);
                });
            }

            if (servers.size() == size) {
                option("[lime]Next", (p, s) -> new ServerListMenu().send(p, s + 1));
            } else {
                option("No more", (p, s) -> {
                    new ServerListMenu().send(p, s);
                    Call.infoToast(p.con, "Please don't click there", 10f);
                });
            }

            row();
            text("[scarlet]Close");
        } catch (Throwable e) {
            Log.err("Failed to build server list menu", e);
        }
    }
}
