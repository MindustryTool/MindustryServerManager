package plugin.gamemode.catali.menu;

import plugin.PluginEvents;
import plugin.annotations.Gamemode;
import plugin.core.Registry;
import plugin.gamemode.catali.data.CataliTeamData;
import plugin.gamemode.catali.event.CataliSpawnRareUpgrade;
import plugin.menus.PluginMenu;
import plugin.service.I18n;
import plugin.type.Session;

@Gamemode("catali")
public class RareUpgradeMenu extends PluginMenu<CataliTeamData> {

    @Override
    public boolean valid() {
        return state.level.rareUpgradePoints > 0;
    }

    @Override
    public void build(Session session, CataliTeamData team) {
        title = I18n.t(session, "@Rare Upgrades");
        description = I18n.t(session, "@Select a rare upgrade type.");

        if (team.canHaveMoreUnit()) {
            option(I18n.t(session, "@Spawn Unit"), (s, st) -> {
                PluginEvents.fire(new CataliSpawnRareUpgrade(team));
            });
        }

        row();

        option(I18n.t(session, "@Evolve Unit"), (s, st) -> {
            Registry.createNew(RareUpgradeTierSelectUnitMenu.class).send(s, team);
        });

        row();

        option(I18n.t(session, "@Apply Buff"), (s, st) -> {
            Registry.createNew(RareUpgradeBuffSelectUnitMenu.class).send(s, team);
        });

        row();

        option(I18n.t(session, "@Close"), (s, st) -> {
        });
    }
}
