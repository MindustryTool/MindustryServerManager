package plugin.gamemode.catali.menu;

import dto.Pair;
import plugin.annotations.Component;
import plugin.core.Registry;
import plugin.gamemode.catali.data.CataliCommonUpgrade;
import plugin.gamemode.catali.data.CataliTeamData;
import plugin.menus.PluginMenu;
import plugin.service.I18n;
import plugin.type.Session;

@Component
public class CommonUpgradeMenu extends PluginMenu<CataliTeamData> {

    @Override
    public void build(Session session, CataliTeamData team) {
        title = I18n.t(session, "@Common Upgrades");
        description = I18n.t(session, "@Select an attribute to upgrade.");

        option(I18n.t(session, "@Damage"), (s, st) -> {
            Registry.get(CommonUpgradeQuantityMenu.class).send(s, Pair.of(team, CataliCommonUpgrade.DAMAGE));
        });
        option(I18n.t(session, "@Health"), (s, st) -> {
            Registry.get(CommonUpgradeQuantityMenu.class).send(s, Pair.of(team, CataliCommonUpgrade.HEALTH));
        });

        row();

        option(I18n.t(session, "@Regeneration"), (s, st) -> {
            Registry.get(CommonUpgradeQuantityMenu.class).send(s, Pair.of(team, CataliCommonUpgrade.HEALING));
        });
        option(I18n.t(session, "@Experience"), (s, st) -> {
            Registry.get(CommonUpgradeQuantityMenu.class).send(s, Pair.of(team, CataliCommonUpgrade.EXPENRIENCE));
        });

        row();

        option(I18n.t(session, "@Close"), (s, st) -> {
        });
    }
}
