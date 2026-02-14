package plugin.gamemode.catali.menu;

import lombok.RequiredArgsConstructor;
import plugin.annotations.Gamemode;
import plugin.gamemode.catali.data.CataliTeamData;
import plugin.menus.PluginMenu;
import plugin.service.I18n;
import plugin.type.Session;

@RequiredArgsConstructor
@Gamemode("catali")
public class AIPickMenu extends PluginMenu<CataliTeamData> {

    @Override
    protected void build(Session session, CataliTeamData team) {
        title = I18n.t(session, "@Choose controller");
        description = I18n.t(session,
                "@Classic controller: tap to move and shoot, double tap to cancel, easier to use\nCommand controller: use game command to control unit, harder to use");

        option(I18n.t(session, "@Classic controller"), (s, t) -> {
            team.useCommandAi = false;
        });
        row();
        option(I18n.t(session, "@Command controller"), (s, t) -> {
            team.useCommandAi = true;
        });
    }
}
