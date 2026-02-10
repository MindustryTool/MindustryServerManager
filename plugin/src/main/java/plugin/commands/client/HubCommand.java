package plugin.commands.client;

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
    private final ApiGateway apiGateway;

    public HubCommand(ApiGateway apiGateway) {
        setName("hub");
        setDescription("Go to hub");
        setAdmin(false);

        this.apiGateway = apiGateway;
    }

    @Override
    public void handle(Session session) {
        var servers = Seq.with(apiGateway.getServers(new PaginationRequest().setPage(0).setSize(20)));

        var hub = servers.find(server -> server.getIsHub());

        if (hub == null) {
            session.player.sendMessage(I18n.t(session, "@Hub not found"));
            return;
        }

        ServerUtils.redirect(session.player, hub);
    }
}
