package plugin.gamemode.catali.menu;

import lombok.RequiredArgsConstructor;
import plugin.annotations.Gamemode;
import plugin.gamemode.catali.CataliGamemode;
import plugin.gamemode.catali.data.CataliTeamData;
import plugin.menus.PluginMenu;
import plugin.service.I18n;
import plugin.type.Session;

@Gamemode("catali")
@RequiredArgsConstructor
public class AbandonMenu extends PluginMenu<CataliTeamData> {

    private final CataliGamemode gamemode;

    @Override
    protected void build(Session session, CataliTeamData team) {
        var units = team.units();

        int i = 0;
        for (var unit : units) {
            option(unit.type.emoji(), (s, t) -> {
                gamemode.abandonUnit(unit);
            });
            i++;
            if (i % 2 == 0) {
                row();
            }
        }
        row();
        option(I18n.t(session, "@Abandon team"), (s, t) -> {
            gamemode.abandonTeam(team);
        });
        row();
        text(I18n.t(session, "@Close"));
    }
}
