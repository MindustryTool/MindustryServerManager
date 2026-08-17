package plugin.gamemode.catali.menu;

import lombok.RequiredArgsConstructor;
import plugin.annotations.Gamemode;
import plugin.gamemode.catali.data.CataliTeamData;
import plugin.menus.PluginMenu;
import plugin.utils.Tr;
import plugin.session.Session;

@RequiredArgsConstructor
@Gamemode("catali")
public class AIPickMenu extends PluginMenu<CataliTeamData> {

    @Override
    protected void build(Session session, CataliTeamData team) {
        title = Tr.t(session, "catali.choose_controller");
        description = Tr.t(session, "catali.controller_description");

        option(Tr.t(session, "catali.classic_controller"), (s, t) -> {
            team.useCommandAi = false;
        });
        row();
        option(Tr.t(session, "catali.command_controller"), (s, t) -> {
            team.useCommandAi = true;
        });
    }
}
