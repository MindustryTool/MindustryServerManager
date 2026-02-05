package plugin.commands.client;

import plugin.Component;
import plugin.Registry;
import plugin.commands.PluginCommand;
import plugin.handler.VoteHandler;
import plugin.menus.RtvMenu;
import plugin.type.Session;

@Component
public class RtvCommand extends PluginCommand {
    Param followParam;

    public RtvCommand() {
        setName("rtv");
        setDescription("Vote to change map");
        setAdmin(false);

        followParam = optional("yes");
    }

    @Override
    public void handleClient(Session session) {
        if (followParam.hasValue() && followParam.asString().equalsIgnoreCase("yes")) {
            Registry.get(VoteHandler.class).handleVote(session.player);
        } else {
            new RtvMenu().send(session, 0);
        }
    }
}
