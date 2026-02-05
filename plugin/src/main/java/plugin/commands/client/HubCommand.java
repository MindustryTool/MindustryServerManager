package plugin.commands.client;

import plugin.type.PaginationRequest;
import plugin.type.Session;
import plugin.utils.ServerUtils;
import arc.struct.Seq;
import plugin.commands.PluginCommand;
import plugin.handler.ApiGateway;
import plugin.handler.I18n;

public class HubCommand extends PluginCommand {
    public HubCommand() {
        setName("hub");
        setDescription("Go to hub");
        setAdmin(false);
    }

    @Override
    public void handleClient(Session session) {
        var servers = Seq.with(ApiGateway.getServers(new PaginationRequest().setPage(0).setSize(20)));

        var hub = servers.find(server -> server.isHub());

        if (hub == null) {
            session.player.sendMessage(I18n.t(session, "@Hub not found"));
            return;
        }

        ServerUtils.redirect(session.player, hub);
    }
}
