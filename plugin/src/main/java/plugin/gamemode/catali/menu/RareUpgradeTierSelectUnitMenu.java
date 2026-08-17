package plugin.gamemode.catali.menu;

import dto.Pair;
import lombok.RequiredArgsConstructor;
import plugin.annotations.Gamemode;
import plugin.core.Registry;
import plugin.gamemode.catali.CataliConfig;
import plugin.gamemode.catali.data.CataliTeamData;
import plugin.menus.PluginMenu;
import plugin.utils.Tr;
import plugin.session.Session;

@Gamemode("catali")
@RequiredArgsConstructor
public class RareUpgradeTierSelectUnitMenu extends PluginMenu<CataliTeamData> {

    private final CataliConfig config;

    @Override
    public void build(Session session, CataliTeamData team) {
        title = Tr.t(session, "catali.select_unit_to_evolve");
        description = Tr.t(session, "catali.choose_unit_to_evolve");

        int i = 0;
        for (var unit : team.getUpgradeableUnits()) {
            if (i > 0 && i % 2 == 0) {
                row();
            }

            var upgrades = config.getUnitEvolutions(unit.type);

            if (upgrades.isEmpty()) {
                continue;
            }

            option(unit.type.emoji() + " " + unit.type.name, (s, st) -> {
                Registry.createNew(RareUpgradeTierSelectUpgradeMenu.class).send(s, Pair.of(team, unit));
            });
            i++;
        }

        row();

        option(Tr.t(session, "catali.back"), (s, st) -> {
            Registry.createNew(RareUpgradeMenu.class).send(s, team);
        });

        option(Tr.t(session, "catali.close"), (s, st) -> {
        });
    }
}
