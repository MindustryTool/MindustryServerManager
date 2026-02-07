package plugin.gamemode.catali.menu;

import dto.Pair;
import plugin.annotations.Gamemode;
import plugin.core.Registry;
import plugin.gamemode.catali.data.CataliTeamData;
import plugin.menus.PluginMenu;
import plugin.service.I18n;
import plugin.type.Session;

@Gamemode("catali")
public class RareUpgradeBuffSelectUnitMenu extends PluginMenu<CataliTeamData> {

    @Override
    public void build(Session session, CataliTeamData team) {
        title = I18n.t(session, "@Select Unit for Buff");
        description = I18n.t(session, "@Choose a unit to apply a buff to.");

        int i = 0;
        for (var unit : team.getTeamUnits()) {
            if (i > 0 && i % 2 == 0) {
                row();
            }

            option(unit.type.emoji() + " " + unit.type.name, (s, st) -> {
                Registry.get(RareUpgradeBuffSelectBuffMenu.class).send(s, Pair.of(team, unit));
            });
            i++;
        }

        row();

        option(I18n.t(session, "@Back"), (s, st) -> {
            Registry.get(RareUpgradeMenu.class).send(s, team);
        });
        option(I18n.t(session, "@Close"), (s, st) -> {
        });
    }
}
