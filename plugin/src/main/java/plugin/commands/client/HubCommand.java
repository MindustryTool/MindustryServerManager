package plugin.commands.client;

import plugin.Registry;
import plugin.annotations.Component;
import plugin.type.PaginationRequest;
import plugin.type.Session;
import plugin.utils.ServerUtils;
import arc.struct.Seq;
import plugin.commands.PluginClientCommand;
import plugin.service.ApiGateway;
import plugin.service.I18n;

@Component
public class HubCommand extends PluginClientCommand {
    public HubCommand() {
        setName("hub");
        setDescription("Go to hub");
        setAdmin(false);
    }

    @Override
    public void handle(Session session) {
        var servers = Seq
                .with(Registry.get(ApiGateway.class).getServers(new PaginationRequest().setPage(0).setSize(20)));

        var hub = servers.find(server -> server.getIsHub());

        if (hub == null) {
            session.player.sendMessage(I18n.t(session, "@Hub not found"));
            return;
        }

        ServerUtils.redirect(session.player, hub);
    }
}
