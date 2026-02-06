package plugin.gamemode.catali.menu;

import arc.Events;
import plugin.annotations.Component;
import plugin.core.Registry;
import plugin.gamemode.catali.CataliGamemode;
import plugin.gamemode.catali.event.CataliSpawnRareUpgrade;
import plugin.menus.PluginMenu;
import plugin.service.I18n;
import plugin.type.Session;

@Component
public class RareUpgradeMenu extends PluginMenu<Void> {

    @Override
    public void build(Session session, Void state) {
        var gamemode = Registry.get(CataliGamemode.class);
        var team = gamemode.findTeam(session.player);

        if (team == null) return;

        title = I18n.t(session, "@Rare Upgrades", session.player);
        description = I18n.t(session, "@Select a rare upgrade type.", session.player);

        // row ---------------------------------------------
        option(I18n.t(session, "@Spawn Unit", session.player), (s, st) -> {
            Events.fire(new CataliSpawnRareUpgrade(team));
        });

        row();

        // row ---------------------------------------------
        option(I18n.t(session, "@Evolve Unit", session.player), (s, st) -> {
            Registry.get(RareUpgradeTierSelectUnitMenu.class).send(s, null);
        });

        row();

        // row ---------------------------------------------
        option(I18n.t(session, "@Apply Buff", session.player), (s, st) -> {
             Registry.get(RareUpgradeBuffSelectUnitMenu.class).send(s, null);
        });

        row();

        // row ---------------------------------------------
        option(I18n.t(session, "@Close", session.player), (s, st) -> {});
    }
}
