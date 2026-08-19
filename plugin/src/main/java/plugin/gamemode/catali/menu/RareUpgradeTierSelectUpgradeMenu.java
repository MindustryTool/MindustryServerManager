package plugin.gamemode.catali.menu;

import dto.Pair;
import lombok.RequiredArgsConstructor;
import mindustry.gen.Unit;
import plugin.PluginEvents;
import plugin.core.Registry;
import plugin.gamemode.catali.CataliConfig;
import plugin.gamemode.catali.data.CataliTeamData;
import plugin.gamemode.catali.event.CataliTierRareUpgrade;
import plugin.menus.PluginMenu;
import plugin.utils.Tr;
import plugin.session.Session;

@RequiredArgsConstructor
public class RareUpgradeTierSelectUpgradeMenu extends PluginMenu<Pair<CataliTeamData, Unit>> {

    private final CataliConfig config;

    @Override
    public void build(Session session, Pair<CataliTeamData, Unit> pair) {
        var team = pair.first;
        var unit = pair.second;

        title = Tr.t(session, "catali.select_evolution");
        description = Tr.t(session, "catali.choose_evolution");

        var availableUnitsUpgradeTo = config.getUnitEvolutions(unit.type);

        int i = 0;
        for (var upgrade : availableUnitsUpgradeTo) {
            if (i > 0 && i % 2 == 0) {
                row();
            }

            option(upgrade.emoji() + " " + upgrade.name, (s, st) -> {
                PluginEvents.fire(new CataliTierRareUpgrade(team, unit, upgrade));
            });
            i++;
        }

        row();

        option(Tr.t(session, "catali.back"), (s, st) -> {
            Registry.inject(RareUpgradeTierSelectUnitMenu.class).send(s, team);
        });
        option(Tr.t(session, "catali.close"), (s, st) -> {
        });
    }
}
