package plugin.vote;

import lombok.RequiredArgsConstructor;
import plugin.annotations.ClientCommand;
import plugin.annotations.Component;
import plugin.annotations.Param;
import plugin.session.Session;
import plugin.utils.Tr;

@Component
@RequiredArgsConstructor
public class VoteCommands {

    private final RtvService voteService;
    private final VoteNewWaveService voteNewWaveService;

    @ClientCommand(name = "rtv", description = "Vote to change map", admin = false)
    public void rtv(Session session, @Param(name = "yes", required = false) String yes) {
        if (yes != null && yes.equalsIgnoreCase("yes")) {
            if (voteService.lastMap == null) {
                session.player.sendMessage(Tr.t(session.locale, "vote.no_map_voted"));
            } else {
                voteService.handleVote(session.player);
            }
        } else {
            new RtvMenu().send(session, 0);
        }
    }

    @ClientCommand(name = "vnw", description = "Vote for sending a new Wave", admin = false)
    public void vnw(Session session, @Param(name = "number", required = false) Integer number) {
        voteNewWaveService.vote(session, number);
    }
}
