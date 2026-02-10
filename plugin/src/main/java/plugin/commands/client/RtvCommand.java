package plugin.commands.client;

import plugin.annotations.Component;
import plugin.commands.PluginClientCommand;
import plugin.menus.RtvMenu;
import plugin.service.VoteService;
import plugin.type.Session;

@Component
public class RtvCommand extends PluginClientCommand {
    private final VoteService voteService;

    private Param followParam;

    public RtvCommand(VoteService voteService) {
        setName("rtv");
        setDescription("Vote to change map");
        setAdmin(false);

        followParam = optional("yes");

        this.voteService = voteService;
    }

    @Override
    public void handle(Session session) {
        if (followParam.hasValue() && followParam.asString().equalsIgnoreCase("yes")) {
            voteService.handleVote(session.player);
        } else {
            new RtvMenu().send(session, 0);
        }
    }
}
