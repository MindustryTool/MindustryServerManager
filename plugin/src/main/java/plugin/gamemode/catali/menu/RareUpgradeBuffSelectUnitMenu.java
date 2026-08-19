package plugin.gamemode.catali.menu;

import dto.Pair;
import plugin.annotations.ConditionOn;
import plugin.gamemode.GamemodeCondition;
import plugin.core.Registry;
import plugin.gamemode.catali.data.CataliTeamData;
import plugin.menus.PluginMenu;
import plugin.utils.Tr;
import plugin.session.Session;

@ConditionOn(value = GamemodeCondition.class, args = {"catali"})
public class RareUpgradeBuffSelectUnitMenu extends PluginMenu<CataliTeamData> {

    @Override
    public void build(Session session, CataliTeamData team) {
        title = Tr.t(session, "catali.select_unit_for_buff");
        description = Tr.t(session, "catali.choose_unit_for_buff");

        int i = 0;
        for (var unit : team.units()) {
            if (i > 0 && i % 2 == 0) {
                row();
            }

            option(unit.type.emoji() + " " + unit.type.name, (s, st) -> {
                Registry.inject(RareUpgradeBuffSelectBuffMenu.class).send(s, Pair.of(team, unit));
            });
            i++;
        }

        row();

        option(Tr.t(session, "catali.back"), (s, st) -> {
            Registry.inject(RareUpgradeMenu.class).send(s, team);
        });
        option(Tr.t(session, "catali.close"), (s, st) -> {
        });
    }
}
