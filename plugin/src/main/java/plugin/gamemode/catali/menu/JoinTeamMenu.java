package plugin.gamemode.catali.menu;

import plugin.menus.PluginMenu;
import plugin.utils.Tr;
import plugin.session.Session;

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
