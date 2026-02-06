package plugin.gamemode.catali.menu;

import plugin.annotations.Component;
import plugin.core.Registry;
import plugin.gamemode.catali.CataliGamemode;
import plugin.gamemode.catali.data.CataliCommonUpgrade;
import plugin.menus.PluginMenu;
import plugin.service.I18n;
import plugin.type.Session;

@Component
public class CommonUpgradeMenu extends PluginMenu<Void> {

    @Override
    public void build(Session session, Void state) {
        var gamemode = Registry.get(CataliGamemode.class);
        var team = gamemode.findTeam(session.player);

        if (team == null)
            return;

        title = I18n.t(session, "@Common Upgrades", session.player);
        description = I18n.t(session, "@Select an attribute to upgrade.", session.player);

        option(I18n.t(session, "@Damage", session.player), (s, st) -> {
            Registry.get(CommonUpgradeQuantityMenu.class).send(s, CataliCommonUpgrade.DAMAGE);
        });
        option(I18n.t(session, "@Health", session.player), (s, st) -> {
            Registry.get(CommonUpgradeQuantityMenu.class).send(s, CataliCommonUpgrade.HEALTH);
        });

        row();

        option(I18n.t(session, "@Regeneration", session.player), (s, st) -> {
            Registry.get(CommonUpgradeQuantityMenu.class).send(s, CataliCommonUpgrade.HEALING);
        });
        option(I18n.t(session, "@Experience", session.player), (s, st) -> {
            Registry.get(CommonUpgradeQuantityMenu.class).send(s, CataliCommonUpgrade.EXPENRIENCE);
        });

        row();

        option(I18n.t(session, "@Close", session.player), (s, st) -> {
        });
    }
}
