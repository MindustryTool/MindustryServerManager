package plugin.commands.client;

import plugin.commands.PluginCommand;
import plugin.handler.VoteHandler;
import plugin.menus.RtvMenu;
import plugin.type.Session;

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
            VoteHandler.handleVote(session.player);
        } else {
            new RtvMenu().send(session, 0);
        }
    }
}
