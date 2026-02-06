package plugin.commands.client;

import plugin.annotations.Component;
import plugin.commands.PluginClientCommand;
import plugin.core.Registry;
import plugin.menus.RtvMenu;
import plugin.service.VoteService;
import plugin.type.Session;

@Component
public class RtvCommand extends PluginClientCommand {
    Param followParam;

    public RtvCommand() {
        setName("rtv");
        setDescription("Vote to change map");
        setAdmin(false);

        followParam = optional("yes");
    }

    @Override
    public void handle(Session session) {
        if (followParam.hasValue() && followParam.asString().equalsIgnoreCase("yes")) {
            Registry.get(VoteService.class).handleVote(session.player);
        } else {
            new RtvMenu().send(session, 0);
        }
    }
}
