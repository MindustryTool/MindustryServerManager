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
public class CommonUpgradeMenu extends PluginMenu<CataliTeamData> {

    @Override
    public boolean valid() {
        return state.level.commonUpgradePoints > 0;
    }

    @Override
    public void build(Session session, CataliTeamData team) {
        title = Tr.t(session, "catali.common_upgrades");
        description = Tr.t(session, "catali.upgrade_points_description", "points", team.level.commonUpgradePoints);

        option(Tr.t(session, "catali.damage"), (s, st) -> {
            if (team.level.commonUpgradePoints == 1) {
                team.consumeUpgrade(CataliCommonUpgrade.DAMAGE, 1);
            } else {
                Registry.createNew(CommonUpgradeQuantityMenu.class).send(s, Pair.of(team, CataliCommonUpgrade.DAMAGE));
            }
        });
        option(Tr.t(session, "catali.health"), (s, st) -> {
            if (team.level.commonUpgradePoints == 1) {
                team.consumeUpgrade(CataliCommonUpgrade.HEALTH, 1);
            } else {
                Registry.createNew(CommonUpgradeQuantityMenu.class).send(s, Pair.of(team, CataliCommonUpgrade.HEALTH));
            }
        });

        row();

        option(Tr.t(session, "catali.regeneration"), (s, st) -> {
            if (team.level.commonUpgradePoints == 1) {
                team.consumeUpgrade(CataliCommonUpgrade.REGEN, 1);
            } else {
                Registry.createNew(CommonUpgradeQuantityMenu.class).send(s, Pair.of(team, CataliCommonUpgrade.REGEN));
            }
        });

        option(Tr.t(session, "catali.experience"), (s, st) -> {
            if (team.level.commonUpgradePoints == 1) {
                team.consumeUpgrade(CataliCommonUpgrade.EXP, 1);
            } else {
                Registry.createNew(CommonUpgradeQuantityMenu.class).send(s, Pair.of(team, CataliCommonUpgrade.EXP));
            }
        });

        row();

        option(Tr.t(session, "catali.close"), (s, st) -> {
        });
    }
}
