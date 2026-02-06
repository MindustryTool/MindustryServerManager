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
public class CommonUpgradeQuantityMenu extends PluginMenu<Pair<CataliTeamData, CataliCommonUpgrade>> {

    @Override
    public void build(Session session, Pair<CataliTeamData, CataliCommonUpgrade> pair) {
        var team = pair.first;
        var upgrade = pair.second;

        var level = team.level;

        title = I18n.t(session, "@Upgrade Quantity");
        description = I18n.t(session, "@Select how many points to spend.");

        option("1", (s, st) -> {
            team.upgrade(upgrade, 1);
            if (level.commonUpgradePoints > 0) {
                Registry.get(CommonUpgradeMenu.class).send(s, team);
            }
        });

        option("2", (s, st) -> {
            team.upgrade(upgrade, 2);
            if (level.commonUpgradePoints > 0) {
                Registry.get(CommonUpgradeMenu.class).send(s, team);
            }
        });

        option("5", (s, st) -> {
            if (level.commonUpgradePoints > 0) {
                team.upgrade(upgrade, 5);
                Registry.get(CommonUpgradeMenu.class).send(s, team);
            }
        });

        row();

        option(I18n.t(session, "@All Points"), (s, st) -> {
            team.upgrade(upgrade, level.commonUpgradePoints);
            Registry.get(CommonUpgradeMenu.class).send(s, team);
        });

        row();

        option(I18n.t(session, "@Back"), (s, st) -> {
            Registry.get(CommonUpgradeMenu.class).send(s, team);
        });
        option(I18n.t(session, "@Close"), (s, st) -> {
        });
    }
}
