package plugin.gamemode.catali.menu;

import plugin.annotations.Component;
import plugin.annotations.ConditionOn;
import plugin.gamemode.GamemodeCondition;
import plugin.menus.PluginMenu;
import plugin.utils.Tr;
import plugin.session.Session;

@Component
@ConditionOn(value = GamemodeCondition.class, args = {"catali"})
public class JoinTeamMenu extends PluginMenu<Void> {

    @Override
    public void build(Session session, Void state) {

        title = Tr.t(session, "catali.join_team");
        description = Tr.t(session, "catali.select_team_leader");

        row();
        option(Tr.t(session, "catali.close"), (s, st) -> {
        });
    }
}
