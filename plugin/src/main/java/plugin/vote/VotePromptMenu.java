package plugin.vote;

import lombok.RequiredArgsConstructor;
import mindustry.ui.builder.UiBuilder;
import plugin.annotations.Component;
import plugin.menus.MenuContext;
import plugin.menus.ServerMenu;
import plugin.utils.Tr;
import plugin.vote.VoteKickService.Vote;

@Component
@RequiredArgsConstructor
public class VotePromptMenu extends ServerMenu<Void> {

    private final VoteKickService voteKickService;

    @Override
    public void build(MenuContext<Void> ctx) {
        var current = voteKickService.getCurrentSession();

        if (current == null) {
            this.title = Tr.t(ctx.getSession(), "votekick.menu_title");
            ctx.label(Tr.t(ctx.getSession(), "votekick.no_vote_in_progress"));
            ctx.row();
            ctx.closeButton(Tr.t(ctx.getSession(), "votekick.btn_close"));
            return;
        }

        this.title = Tr.t(ctx.getSession(), "votekick.prompt_title", "target", current.target.name);

        ctx.label(Tr.t(ctx.getSession(), "votekick.prompt_desc",
            "initiator", current.initiator.name,
            "reason", current.reason,
            "votes", voteKickService.getVotes(),
            "required", voteKickService.getVotesRequired(),
            "time", voteKickService.getRemainingSeconds()));
        ctx.row();

        ctx.table(actions -> {
            String yesAct = ctx.registerAction((sess, st, res) -> voteKickService.vote(sess.player, Vote.YES));
            String noAct = ctx.registerAction((sess, st, res) -> voteKickService.vote(sess.player, Vote.NO));

            actions.add(UiBuilder.button("[green]" + Tr.t(ctx.getSession(), "votekick.btn_yes")).clicked(yesAct).width(95f).height(44f).pad(4f));
            actions.add(UiBuilder.button("[scarlet]" + Tr.t(ctx.getSession(), "votekick.btn_no")).clicked(noAct).width(95f).height(44f).pad(4f));

            if (ctx.getSession().player.admin) {
                String cancelAct = ctx.registerAction((sess, st, res) -> voteKickService.cancelByAdmin(sess.player));
                actions.add(UiBuilder.button("[gold]" + Tr.t(ctx.getSession(), "votekick.btn_cancel")).clicked(cancelAct).width(95f).height(44f).pad(4f));
            }
        });

        ctx.row();
        ctx.closeButton(Tr.t(ctx.getSession(), "votekick.btn_close"));
    }
}
