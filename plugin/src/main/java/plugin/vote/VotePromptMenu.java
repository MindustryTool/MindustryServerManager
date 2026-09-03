package plugin.vote;

import plugin.core.Registry;
import plugin.menus.PluginMenu;
import plugin.session.Session;
import plugin.utils.Tr;

public class VotePromptMenu extends PluginMenu<Void> {

    @Override
    public void build(Session session, Void state) {
        var voteKickService = Registry.get(VoteKickService.class);
        var current = voteKickService.getCurrentSession();

        if (current == null) {
            this.title = Tr.t(session, "votekick.menu_title");
            this.description = Tr.t(session, "votekick.no_vote_in_progress");
            text(Tr.t(session, "votekick.btn_close"));
            return;
        }

        this.title = Tr.t(session, "votekick.prompt_title", "target", current.target.name);
        this.description = Tr.t(session, "votekick.prompt_desc",
                "initiator", current.initiator.name,
                "reason", current.reason,
                "votes", voteKickService.getVotes(),
                "required", voteKickService.getVotesRequired(),
                "time", voteKickService.getRemainingSeconds());

        option(Tr.t(session, "votekick.btn_yes"), (s, st) -> {
            voteKickService.vote(s.player, 1);
        });

        option(Tr.t(session, "votekick.btn_no"), (s, st) -> {
            voteKickService.vote(s.player, -1);
        });

        if (session.player.admin) {
            option(Tr.t(session, "votekick.btn_cancel"), (s, st) -> {
                voteKickService.cancelByAdmin(s.player);
            });
        }
        row();

        text(Tr.t(session, "votekick.btn_close"));
    }
}
