package plugin.gamemode.catali.menu;

import plugin.annotations.Gamemode;
import plugin.menus.PluginMenu;
import plugin.service.I18n;
import plugin.type.Session;

@Gamemode("catali")
public class JoinTeamMenu extends PluginMenu<Void> {

    @Override
    public void build(Session session, Void state) {
        
        title = I18n.t(session, "@Join Team");
        description = I18n.t(session, "@Select a team leader to request joining.");

        row();
        option(I18n.t(session, "@Close"), (s, st) -> {
        });
    }
}
