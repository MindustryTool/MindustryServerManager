package plugin.gamemode.catali.menu;

import dto.Pair;
import plugin.annotations.Gamemode;
import plugin.core.Registry;
import plugin.gamemode.catali.data.CataliCommonUpgrade;
import plugin.gamemode.catali.data.CataliTeamData;
import plugin.menus.PluginMenu;
import plugin.utils.Tr;
import plugin.session.Session;

@Gamemode("catali")
public class CommonUpgradeQuantityMenu extends PluginMenu<Pair<CataliTeamData, CataliCommonUpgrade>> {

    @Override
    public void build(Session session, Pair<CataliTeamData, CataliCommonUpgrade> pair) {
        var team = pair.first;
        var upgrade = pair.second;

        var level = team.level;

        title = Tr.t(session, "catali.upgrade_quantity");
        description = Tr.t(session, "catali.select_points_to_spend");

        option("1", (s, st) -> {
            team.consumeUpgrade(upgrade, 1);
            if (level.commonUpgradePoints > 0) {
                Registry.inject(CommonUpgradeMenu.class).send(s, team);
            }
        });

        if (level.commonUpgradePoints >= 2) {
            option("2", (s, st) -> {
                team.consumeUpgrade(upgrade, 2);
                if (level.commonUpgradePoints > 0) {
                    Registry.inject(CommonUpgradeMenu.class).send(s, team);
                }
            });
        }

        if (level.commonUpgradePoints >= 5) {
            option("5", (s, st) -> {
                team.consumeUpgrade(upgrade, 5);
                if (level.commonUpgradePoints > 0) {
                    Registry.inject(CommonUpgradeMenu.class).send(s, team);
                }
            });
        }

        row();

        option(Tr.t(session, "catali.all_points"), (s, st) -> {
            team.consumeUpgrade(upgrade, level.commonUpgradePoints);
            if (level.commonUpgradePoints > 0) {
                Registry.inject(CommonUpgradeMenu.class).send(s, team);
            }
        });

        row();

        option(Tr.t(session, "catali.back"), (s, st) -> {
            Registry.inject(CommonUpgradeMenu.class).send(s, team);
        });
        option(Tr.t(session, "catali.close"), (s, st) -> {
        });
    }
}
