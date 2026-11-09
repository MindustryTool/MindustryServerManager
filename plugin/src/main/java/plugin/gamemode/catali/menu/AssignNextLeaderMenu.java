package plugin.gamemode.catali.menu;

import java.util.Objects;

import arc.struct.Seq;
import lombok.RequiredArgsConstructor;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.ui.builder.UiBuilder;
import plugin.annotations.Component;
import plugin.annotations.ConditionOn;
import plugin.gamemode.GamemodeCondition;
import plugin.gamemode.catali.data.CataliTeamData;
import plugin.menus.MenuContext;
import plugin.menus.ServerMenu;
import plugin.session.Session;
import plugin.utils.Tr;

@Component
@RequiredArgsConstructor
@ConditionOn(value = GamemodeCondition.class, args = {"catali"})
public class AssignNextLeaderMenu extends ServerMenu<CataliTeamData> {

    @Override
    public void build(MenuContext<CataliTeamData> ctx) {
        Session session = ctx.getSession();
        CataliTeamData team = ctx.getState();
        Objects.requireNonNull(session);
        Objects.requireNonNull(team);

        this.title = Tr.t(session, "catali.assign_next_leader");

        var availableTeammates = new Seq<Player>();
        for (var uuid : team.members) {
            if (uuid.equals(session.player.uuid()) || uuid.equals(team.nextLeaderUuid)) {
                continue;
            }
            var p = Groups.player.find(pl -> pl.uuid().equals(uuid));
            if (p != null) {
                availableTeammates.add(p);
            }
        }

        if (availableTeammates.isEmpty()) {
            ctx.label("[lightgray]" + Tr.t(session, "catali.no_teammates"));
            return;
        }

        ctx.label("[lightgray]" + Tr.t(session, "catali.select_next_leader"));
        ctx.row();

        ctx.pane(MenuContext.SAFE_WIDTH, 220f, paneTable -> {
            for (var p : availableTeammates) {
                var card = UiBuilder.table().align("left");
                card.width(MenuContext.SAFE_CARD_WIDTH);

                card.add(UiBuilder.label("[white]" + p.name() + "[]").wrap().align("left").growX().padLeft(4f));

                String actId = ctx.registerAction((sess, t, res) -> {
                    team.assignNextLeader(p.uuid());
                    sess.player.sendMessage(Tr.t(sess, "catali.leadership_transferred", "name", p.name()));
                });

                card.add(UiBuilder.button(Tr.t(session, "catali.assign_leader"))
                        .clicked(actId)
                        .width(115f)
                        .height(34f)
                        .align("right"));

                paneTable.add(card.growX().pad(2f)).row();
            }
        });
    }
}
