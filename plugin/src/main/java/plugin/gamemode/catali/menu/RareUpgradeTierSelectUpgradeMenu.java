package plugin.gamemode.catali.menu;

import dto.Pair;
import lombok.RequiredArgsConstructor;
import mindustry.gen.Unit;
import plugin.PluginEvents;
import plugin.annotations.Gamemode;
import plugin.core.Registry;
import plugin.gamemode.catali.CataliConfig;
import plugin.gamemode.catali.data.CataliTeamData;
import plugin.gamemode.catali.event.CataliTierRareUpgrade;
import plugin.menus.PluginMenu;
import plugin.service.I18n;
import plugin.type.Session;

@Gamemode("catali")
@RequiredArgsConstructor
public class RareUpgradeTierSelectUpgradeMenu extends PluginMenu<Pair<CataliTeamData, Unit>> {

    private final CataliConfig config;

    @Override
    public void build(Session session, Pair<CataliTeamData, Unit> pair) {
        var team = pair.first;
        var unit = pair.second;

        title = I18n.t(session, "@Select Evolution");
        description = I18n.t(session, "@Choose what this unit should evolve into.");

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

        option(I18n.t(session, "@Back"), (s, st) -> {
            Registry.createNew(RareUpgradeTierSelectUnitMenu.class).send(s, team);
        });
        option(I18n.t(session, "@Close"), (s, st) -> {
        });
    }
}
