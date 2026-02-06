package plugin.gamemode.catali.menu;

import arc.struct.Seq;
import mindustry.gen.Unit;
import plugin.annotations.Component;
import plugin.core.Registry;
import plugin.gamemode.catali.CataliConfig;
import plugin.menus.PluginMenu;
import plugin.service.I18n;
import plugin.type.Session;

@Component
public class RareUpgradeTierSelectUnitMenu extends PluginMenu<Seq<Unit>> {

    @Override
    public void build(Session session, Seq<Unit> availableUnitsToUpgrade) {
        title = I18n.t(session, "@Select Unit to Evolve", session.player);
        description = I18n.t(session, "@Choose a unit to evolve.", session.player);

        int i = 0;
        for (var unit : availableUnitsToUpgrade) {
            if (i > 0 && i % 2 == 0) {
                row();
            }

            var config = Registry.get(CataliConfig.class);
            var upgrades = config.getUnitEvolutions(unit.type);

            option(unit.type.emoji() + " " + unit.type.name, (s, st) -> {
                Registry.get(RareUpgradeTierSelectUpgradeMenu.class).send(s, Seq.with(upgrades));
            });
            i++;
        }

        row();

        option(I18n.t(session, "@Back", session.player), (s, st) -> {
            Registry.get(RareUpgradeMenu.class).send(s, null);
        });

        option(I18n.t(session, "@Close", session.player), (s, st) -> {
        });
    }
}
