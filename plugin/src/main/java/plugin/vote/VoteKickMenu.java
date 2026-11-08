package plugin.vote;

import arc.struct.Seq;
import lombok.RequiredArgsConstructor;
import mindustry.Vars;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.ui.builder.UiBuilder;
import mindustry.ui.builder.UiBuilder.TableBuilder;
import plugin.annotations.Component;
import plugin.core.Registry;
import plugin.menus.MenuContext;
import plugin.menus.ServerMenu;
import plugin.session.LoginMenu;
import plugin.utils.Tr;

@Component
@RequiredArgsConstructor
public class VoteKickMenu extends ServerMenu<Player> {

    private final VoteKickService voteKickService;

    @Override
    public void build(MenuContext<Player> ctx) {
        if (!ctx.getSession().isLoggedIn()) {
            ctx.getSession().player.sendMessage(Tr.t(ctx.getSession(), "votekick.login_required"));
            Registry.get(LoginMenu.class).send(ctx.getSession(), null);
            return;
        }

        this.title = Tr.t(ctx.getSession(), "votekick.menu_title");
        Player target = ctx.getState();

        if (target == null) {
            ctx.label(Tr.t(ctx.getSession(), "votekick.menu_select_player")).width(MenuContext.SAFE_WIDTH);
            ctx.row();

            Seq<Player> candidates = new Seq<>();
            for (Player p : Groups.player) {
                if (p != ctx.getSession().player && !p.admin && !p.isLocal()
                        && (!Vars.state.rules.pvp || p.team() == ctx.getSession().player.team())) {
                    candidates.add(p);
                }
            }

            ctx.pane(MenuContext.SAFE_WIDTH, 280f, paneTable -> {
                if (candidates.isEmpty()) {
                    paneTable.add(UiBuilder.label("[lightgray]No eligible players to votekick").wrap().align("left").growX().pad(16f));
                    return;
                }

                for (Player candidate : candidates) {
                    TableBuilder card = UiBuilder.table().align("left");
                    card.width(MenuContext.SAFE_CARD_WIDTH);
                    card.add(UiBuilder.label("[white]" + candidate.name + " [gray](#" + candidate.id() + ")").wrap().align("left").growX().padLeft(4f));

                    String kickAct = ctx.registerAction((sess, st, res) -> Registry.get(VoteKickMenu.class).send(sess, candidate));
                    card.add(UiBuilder.button("Select").clicked(kickAct).width(90f).height(36f).align("right").padLeft(6f));

                    paneTable.add(card.growX().pad(2f)).row();
                }
            });
        } else {
            ctx.label(Tr.t(ctx.getSession(), "votekick.menu_select_reason", "target", target.name)).width(MenuContext.SAFE_WIDTH);
            ctx.row();

            String[] reasonKeys = {
                "votekick.reason_griefing",
                "votekick.reason_afk",
                "votekick.reason_toxicity",
                "votekick.reason_other"
            };

            ctx.table(reasonsTable -> {
                for (String key : reasonKeys) {
                    String reasonText = Tr.t(ctx.getSession(), key);
                    String act = ctx.registerAction((sess, st, res) -> voteKickService.startVote(sess.player, target, reasonText));
                    reasonsTable.add(UiBuilder.button(reasonText).clicked(act).growX().height(38f).pad(3f)).row();
                }
            });

            ctx.row();
            ctx.button(Tr.t(ctx.getSession(), "votekick.btn_back"), (sess, st, res) -> Registry.get(VoteKickMenu.class).send(sess, null))
                .width(120f).height(36f).pad(3f);
        }
    }
}
