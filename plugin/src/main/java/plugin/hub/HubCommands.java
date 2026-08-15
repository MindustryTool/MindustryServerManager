package plugin.hub;

import arc.struct.Seq;
import lombok.RequiredArgsConstructor;
import plugin.annotations.ClientCommand;
import plugin.annotations.Component;
import plugin.gateway.ApiGateway;
import plugin.session.Session;
import plugin.utils.I18n;

@Component
@RequiredArgsConstructor
public class HubCommands {

    private final ApiGateway apiGateway;

    @ClientCommand(name = "hub", description = "Go to hub", admin = false)
    public void hub(Session session) {
        var servers = Seq.with(apiGateway.getServers(new PaginationRequest().setPage(0).setSize(20)));

        var hub = servers.find(server -> server.getIsHub());

        if (hub == null) {
            session.player.sendMessage(I18n.t(session, "@Hub not found"));
            return;
        }

        ServerUtils.redirect(session.player, hub);
    }

    @ClientCommand(name = "servers", description = "Display available servers", admin = false)
    public void servers(Session session) {
        new ServerListMenu().send(session, 0);
    }

    @ClientCommand(name = "redirect", description = "Redirect all player to server")
    public void redirect(Session session) {
        new GlobalServerListMenu(server -> ServerUtils.redirectAll(server)).send(session, 0);
    }
}