package plugin.gamemode.catali.menu;

import arc.struct.Seq;
import mindustry.gen.Unit;
import plugin.annotations.Component;
import plugin.core.Registry;
import plugin.menus.PluginMenu;
import plugin.service.I18n;
import plugin.type.Session;

@Component
public class RareUpgradeBuffSelectUnitMenu extends PluginMenu<Seq<Unit>> {

    @Override
    public void build(Session session, Seq<Unit> availableUnitsToUpgrade) {
        title = I18n.t(session, "@Select Unit for Buff", session.player);
        description = I18n.t(session, "@Choose a unit to apply a buff to.", session.player);

        int i = 0;
        for (var unit : availableUnitsToUpgrade) {
            if (i > 0 && i % 2 == 0)
                row();

            option(unit.type.emoji() + " " + unit.type.name, (s, st) -> {
                Registry.get(RareUpgradeBuffSelectBuffMenu.class).send(s, unit);
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
