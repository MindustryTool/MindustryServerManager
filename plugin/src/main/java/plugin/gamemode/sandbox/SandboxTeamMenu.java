package plugin.gamemode.sandbox;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import mindustry.game.Team;
import mindustry.ui.builder.UiBuilder;
import plugin.annotations.Component;
import plugin.annotations.ConditionOn;
import plugin.gamemode.GamemodeCondition;
import plugin.menus.MenuContext;
import plugin.menus.ServerMenu;
import plugin.session.Session;
import plugin.utils.Tr;

@Component
@ConditionOn(value = GamemodeCondition.class, args = {"sandbox"})
public class SandboxTeamMenu extends ServerMenu<Void> {

    public static final List<Team> PLAYABLE_TEAMS = Arrays.asList(
            Team.sharded,
            Team.crux,
            Team.malis,
            Team.green,
            Team.blue,
            Team.neoplastic
    );

    @Override
    public void build(MenuContext<Void> ctx) {
        Session session = ctx.getSession();
        Objects.requireNonNull(session);

        this.title = Tr.t(session, "sandbox.team_menu_title");

        ctx.label("[lightgray]" + Tr.t(session, "sandbox.team_menu_description"));
        ctx.row();

        ctx.pane(MenuContext.SAFE_WIDTH, 260f, paneTable -> {
            Team currentTeam = session.player != null ? session.player.team() : null;

            for (Team team : PLAYABLE_TEAMS) {
                boolean isCurrent = currentTeam == team;
                var card = UiBuilder.table().pad(4f);
                card.width(MenuContext.SAFE_CARD_WIDTH);

                card.add(UiBuilder.label(team.coloredName()).wrap().align("left").growX().pad(4f));

                if (isCurrent) {
                    card.add(UiBuilder.label("[stat](Current)[]").align("right").pad(4f).padLeft(8f));
                } else {
                    String actId = ctx.registerAction((sess, st, res) -> {
                        if (sess.player != null) {
                            sess.player.team(team);
                            sess.player.sendMessage(Tr.t(sess, "sandbox.team_changed", "team", team.coloredName()));
                        }
                    });
                    card.add(UiBuilder.button("[accent]Switch[]").clicked(actId).width(95f).height(36f).align("right").pad(4f));
                }

                paneTable.add(card.growX().pad(2f)).row();
            }
        });
    }
}
