package plugin.gamemode.catali.menu;

import dto.Pair;
import mindustry.gen.Unit;
import plugin.PluginEvents;
import plugin.core.Registry;
import plugin.gamemode.catali.CataliConfig;
import plugin.gamemode.catali.data.CataliTeamData;
import plugin.gamemode.catali.event.CataliBuffRareUpgrade;
import plugin.menus.PluginMenu;
import plugin.utils.Tr;
import plugin.session.Session;

public class RareUpgradeBuffSelectBuffMenu extends PluginMenu<Pair<CataliTeamData, Unit>> {

    @Override
    public void build(Session session, Pair<CataliTeamData, Unit> pair) {
        var team = pair.first;
        var unit = pair.second;

        var availableEffectsAppliedTo = CataliConfig.selectBuffsCanBeApplied(unit);

        title = Tr.t(session, "catali.select_buff");
        description = Tr.t(session, "catali.choose_buff");

        int i = 0;
        for (var effect : availableEffectsAppliedTo) {
            if (i > 0 && i % 2 == 0)
                row();

            option(effect.emoji() + " " + effect.name, (s, st) -> {
                PluginEvents.fire(new CataliBuffRareUpgrade(team, unit, effect));
            });
            i++;
        }

        row();

        option(Tr.t(session, "catali.back"), (s, st) -> {
            Registry.inject(RareUpgradeBuffSelectUnitMenu.class).send(s, team);
        });
        option(Tr.t(session, "catali.close"), (s, st) -> {
        });
    }
}
