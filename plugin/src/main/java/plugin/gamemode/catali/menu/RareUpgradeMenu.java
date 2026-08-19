package plugin.gamemode.catali.menu;

import plugin.PluginEvents;
import plugin.annotations.Gamemode;
import plugin.core.Registry;
import plugin.gamemode.catali.data.CataliTeamData;
import plugin.gamemode.catali.event.CataliSpawnRareUpgrade;
import plugin.menus.PluginMenu;
import plugin.utils.Tr;
import plugin.session.Session;

@Gamemode("catali")
public class RareUpgradeMenu extends PluginMenu<CataliTeamData> {

    @Override
    public boolean valid() {
        return state.level.rareUpgradePoints > 0;
    }

    @Override
    public void build(Session session, CataliTeamData team) {
        title = Tr.t(session, "catali.rare_upgrades");
        description = Tr.t(session, "catali.select_rare_upgrade_type");

        if (team.canHaveMoreUnit()) {
            option(Tr.t(session, "catali.spawn_unit"), (s, st) -> {
                PluginEvents.fire(new CataliSpawnRareUpgrade(team));
            });
        }

        row();

        option(Tr.t(session, "catali.evolve_unit"), (s, st) -> {
            Registry.inject(RareUpgradeTierSelectUnitMenu.class).send(s, team);
        });

        row();

        option(Tr.t(session, "catali.apply_buff"), (s, st) -> {
            Registry.inject(RareUpgradeBuffSelectUnitMenu.class).send(s, team);
        });

        row();

        option(Tr.t(session, "catali.close"), (s, st) -> {
        });
    }
}
