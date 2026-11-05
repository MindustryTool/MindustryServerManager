package plugin.gamemode.catali.menu;

import java.util.Objects;

import lombok.RequiredArgsConstructor;
import plugin.annotations.Component;
import plugin.annotations.ConditionOn;
import plugin.gamemode.GamemodeCondition;
import plugin.menus.MenuContext;
import plugin.menus.ServerMenu;
import plugin.session.Session;
import plugin.utils.Tr;

@Component
@RequiredArgsConstructor
@ConditionOn(value = GamemodeCondition.class, args = {"catali"})
public class JoinTeamMenu extends ServerMenu<Void> {

    @Override
    public void build(MenuContext<Void> ctx) {
        Session session = ctx.getSession();
        Objects.requireNonNull(session);

        this.title = Tr.t(session, "catali.join_team");

        ctx.label("[lightgray]" + Tr.t(session, "catali.select_team_leader"));
        ctx.row();
        ctx.closeButton(Tr.t(session, "catali.close"));
    }
}
