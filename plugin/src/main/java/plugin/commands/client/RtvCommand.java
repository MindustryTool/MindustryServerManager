package plugin.commands.client;

import mindustry.gen.Player;
import plugin.commands.PluginCommand;
import plugin.handler.VoteHandler;
import plugin.menus.RtvMenu;

public class RtvCommand extends PluginCommand {
    Param followParam;

    public RtvCommand() {
        setName("rtv");
        setDescription("Vote to change map");

        followParam = optional("yes");
    }

    @Override
    public void handleClient(Player player) {
        if (followParam.hasValue() && followParam.asString().equalsIgnoreCase("yes")) {
            VoteHandler.handleVote(player);
        } else {
            new RtvMenu().send(player, 0);
        }
    }
}
