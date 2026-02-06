package plugin.gamemode.catali.menu;

import arc.struct.Seq;
import mindustry.type.UnitType;
import plugin.annotations.Component;
import plugin.core.Registry;
import plugin.menus.PluginMenu;
import plugin.service.I18n;
import plugin.type.Session;

@Component
public class RareUpgradeTierSelectUpgradeMenu extends PluginMenu<Seq<UnitType>> {

    @Override
    public void build(Session session, Seq<UnitType> availableUnitsUpgradeTo) {
        title = I18n.t(session, "@Select Evolution", session.player);
        description = I18n.t(session, "@Choose what this unit should evolve into.", session.player);

        int i = 0;
        for (var upgrade : availableUnitsUpgradeTo) {
            if (i > 0 && i % 2 == 0)
                row();

            option(upgrade.emoji() + " " + upgrade.name, (s, st) -> {

            });
            i++;
        }

        row();

        option(I18n.t(session, "@Back", session.player), (s, st) -> {
            Registry.get(RareUpgradeTierSelectUnitMenu.class).send(s, null);
        });
        option(I18n.t(session, "@Close", session.player), (s, st) -> {
        });
    }
}
