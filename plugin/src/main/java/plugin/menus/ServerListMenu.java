package plugin.menus;

import arc.util.Log;
import mindustry.gen.Call;
import plugin.Config;
import plugin.commands.ClientCommandHandler;
import plugin.core.Registry;
import plugin.service.ApiGateway;
import plugin.service.I18n;
import plugin.type.PaginationRequest;
import plugin.type.Session;
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
            this.description = I18n.t(session.locale, "@" + Config.CHOOSE_SERVER_MESSAGE);

            var clientCommandHandler = Registry.get(ClientCommandHandler.class);

            servers.stream().sorted(Comparator.comparing(ServerDto::getPlayers).reversed()).forEach(server -> {
                row();
                var mods = server.getMods();

                if (mods != null) {
                    mods.removeIf(m -> m.trim().equalsIgnoreCase("mindustrytoolplugin"));
                }

                if (server.getMapName() == null) {
                    option(String.format("[yellow]%s", server.getName()),
                            (p, s) -> clientCommandHandler.onServerChoose(p, server.getId().toString(),
                                    server.getName()));
                    option(I18n.t(session.locale, "[scarlet]", "@Server offline."),
                            (p, s) -> clientCommandHandler.onServerChoose(p, server.getId().toString(),
                                    server.getName()));
                } else {
                    option(server.getName(),
                            (p, s) -> clientCommandHandler.onServerChoose(p, server.getId().toString(),
                                    server.getName()));
                    option(I18n.t(session.locale, "[lime]", "@Players:[] ", server.getPlayers()),
                            (p, s) -> clientCommandHandler.onServerChoose(p, server.getId().toString(),
                                    server.getName()));

                    row();
                    option(I18n.t(session.locale, "[cyan]", "@Gamemode:[] ",
                            server.getMode().toLowerCase()),
                            (p, s) -> clientCommandHandler.onServerChoose(p, server.getId().toString(),
                                    server.getName()));
                    option(I18n.t(session.locale, "[blue]", "@Map:[] ", server.getMapName()),
                            (p, s) -> clientCommandHandler.onServerChoose(p, server.getId().toString(),
                                    server.getName()));
                }

                if (server.getMods() != null && !server.getMods().isEmpty()) {
                    row();
                    option(I18n.t(session.locale, "[purple]", "@Mods:[] ", String.join(", ", mods)),
                            (p, s) -> clientCommandHandler.onServerChoose(p, server.getId().toString(),
                                    server.getName()));
                }

                if (server.getDescription() != null && !server.getDescription().trim().isEmpty()) {
                    row();
                    option(String.format("[grey]%s", server.getDescription()),
                            (p, s) -> clientCommandHandler.onServerChoose(p, server.getId().toString(),
                                    server.getName()));
                }

                row();
                text("");
            });

            row();
            if (page > 0) {
                option(I18n.t(session.locale, "[orange]", "@Previous"),
                        (p, s) -> new ServerListMenu().send(p, s - 1));
            } else {
                option(I18n.t(session.locale, "@First page"), (p, s) -> {
                    new ServerListMenu().send(p, s);
                    Call.infoToast(p.player.con, I18n.t(session.locale, "@Please don't click there"),
                            10f);
                });
            }

            if (servers.size() == size) {
                option(I18n.t(session.locale, "[lime]", "@Next"),
                        (p, s) -> new ServerListMenu().send(p, s + 1));
            } else {
                option(I18n.t(session.locale, "@No more"), (p, s) -> {
                    new ServerListMenu().send(p, s);
                    Call.infoToast(p.player.con, I18n.t(session.locale, "@Please don't click there"),
                            10f);
                });
            }

            row();
            text(I18n.t(session.locale, "[scarlet]", "@Close"));
        } catch (Throwable e) {
            Log.err("Failed to build server list menu", e);
        }
    }
}
