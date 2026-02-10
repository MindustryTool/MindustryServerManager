package plugin.gamemode.catali.menu;

import dto.Pair;
import plugin.annotations.Gamemode;
import plugin.core.Registry;
import plugin.gamemode.catali.data.CataliCommonUpgrade;
import plugin.gamemode.catali.data.CataliTeamData;
import plugin.menus.PluginMenu;
import plugin.service.I18n;
import plugin.type.Session;

@Gamemode("catali")
public class CommonUpgradeMenu extends PluginMenu<CataliTeamData> {

    @Override
    public boolean valid() {
        return state.level.commonUpgradePoints > 0;
    }

    @Override
    public void build(Session session, CataliTeamData team) {
        title = I18n.t(session, "@Common Upgrades");
        description = I18n.t(session, "@You have", team.level.commonUpgradePoints, "@upgrade points",
                "@Select an attribute to upgrade.");

        option(I18n.t(session, "@Damage"), (s, st) -> {
            if (team.level.commonUpgradePoints == 1) {
                team.consumeUpgrade(CataliCommonUpgrade.DAMAGE, 1);
            } else {
                Registry.createNew(CommonUpgradeQuantityMenu.class).send(s, Pair.of(team, CataliCommonUpgrade.DAMAGE));
            }
        });
        option(I18n.t(session, "@Health"), (s, st) -> {
            if (team.level.commonUpgradePoints == 1) {
                team.consumeUpgrade(CataliCommonUpgrade.HEALTH, 1);
            } else {
                Registry.createNew(CommonUpgradeQuantityMenu.class).send(s, Pair.of(team, CataliCommonUpgrade.HEALTH));
            }
        });

        row();

        option(I18n.t(session, "@Regeneration"), (s, st) -> {
            if (team.level.commonUpgradePoints == 1) {
                team.consumeUpgrade(CataliCommonUpgrade.REGEN, 1);
            } else {
                Registry.createNew(CommonUpgradeQuantityMenu.class).send(s, Pair.of(team, CataliCommonUpgrade.REGEN));
            }
        });

        option(I18n.t(session, "@Experience"), (s, st) -> {
            if (team.level.commonUpgradePoints == 1) {
                team.consumeUpgrade(CataliCommonUpgrade.EXP, 1);
            } else {
                Registry.createNew(CommonUpgradeQuantityMenu.class).send(s, Pair.of(team, CataliCommonUpgrade.EXP));
            }
        });

        row();

        option(I18n.t(session, "@Close"), (s, st) -> {
        });
    }
}
