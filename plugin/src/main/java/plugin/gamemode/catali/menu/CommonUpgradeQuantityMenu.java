package plugin.gamemode.catali.menu;

import plugin.annotations.Component;
import plugin.core.Registry;
import plugin.gamemode.catali.CataliGamemode;
import plugin.gamemode.catali.data.CataliCommonUpgrade;
import plugin.gamemode.catali.data.CataliTeamData;
import plugin.menus.PluginMenu;
import plugin.service.I18n;
import plugin.type.Session;

@Component
public class CommonUpgradeQuantityMenu extends PluginMenu<CataliCommonUpgrade> {

    @Override
    public void build(Session session, CataliCommonUpgrade upgrade) {
        var gamemode = Registry.get(CataliGamemode.class);
        var team = gamemode.findTeam(session.player);
        if (team == null)
            return;

        var level = team.level;

        if (level.commonUpgradePoints < 5) {
            useCommonUpgrade(team, upgrade, 1);
            Registry.get(CommonUpgradeMenu.class).send(session, null);
            return;
        }

        title = I18n.t(session, "@Upgrade Quantity", session.player);
        description = I18n.t(session, "@Select how many points to spend.", session.player);

        option("1", (s, st) -> {
            useCommonUpgrade(team, upgrade, 1);
            Registry.get(CommonUpgradeMenu.class).send(s, null);
        });
        option("2", (s, st) -> {
            useCommonUpgrade(team, upgrade, 2);
            Registry.get(CommonUpgradeMenu.class).send(s, null);
        });
        option("5", (s, st) -> {
            useCommonUpgrade(team, upgrade, 5);
            Registry.get(CommonUpgradeMenu.class).send(s, null);
        });

        row();

        option(I18n.t(session, "@All Points", session.player), (s, st) -> {
            useCommonUpgrade(team, upgrade, level.commonUpgradePoints);
            Registry.get(CommonUpgradeMenu.class).send(s, null);
        });

        row();

        option(I18n.t(session, "@Back", session.player), (s, st) -> {
            Registry.get(CommonUpgradeMenu.class).send(s, null);
        });
        option(I18n.t(session, "@Close", session.player), (s, st) -> {
        });
    }

    private void useCommonUpgrade(CataliTeamData team, CataliCommonUpgrade upgrade, int amount) {
        if (team.level.commonUpgradePoints < amount)
            return;

        team.level.commonUpgradePoints -= amount;
        var upgrades = team.upgrades;

        switch (upgrade) {
            case DAMAGE:
                upgrades.damageLevel += amount;
                upgrades.damageMultiplier += 0.1f * amount;
                break;
            case HEALTH:
                upgrades.healthLevel += amount;
                upgrades.healthMultiplier += 0.1f * amount;
                break;
            case HEALING:
                upgrades.regenLevel += amount;
                upgrades.regenMultiplier += 0.1f * amount;
                break;
            case EXPENRIENCE:
                upgrades.expLevel += amount;
                upgrades.expMultiplier += 0.1f * amount;
                break;
        }
    }
}
