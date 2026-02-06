package plugin.gamemode.catali.menu;

import arc.Events;
import mindustry.gen.Unit;
import plugin.annotations.Component;
import plugin.core.Registry;
import plugin.gamemode.catali.CataliConfig;
import plugin.gamemode.catali.CataliGamemode;
import plugin.gamemode.catali.event.CataliBuffRareUpgrade;
import plugin.menus.PluginMenu;
import plugin.service.I18n;
import plugin.type.Session;

@Component
public class RareUpgradeBuffSelectBuffMenu extends PluginMenu<Unit> {

    @Override
    public void build(Session session, Unit unit) {
        if (unit == null || unit.dead()) return;
        
        var gamemode = Registry.get(CataliGamemode.class);
        var team = gamemode.findTeam(session.player);
        
        if (team == null) return;

        var availableEffectsAppliedTo = CataliConfig.selectBuffsCanBeApplied(unit);

        title = I18n.t(session, "@Select Buff", session.player);
        description = I18n.t(session, "@Choose a buff to apply to the unit.", session.player);

        int i = 0;
        for (var effect : availableEffectsAppliedTo) {
            if (i > 0 && i % 2 == 0) row();
            
            option(effect.emoji() + " " + effect.name, (s, st) -> {
                Events.fire(new CataliBuffRareUpgrade(team, unit, effect));
            });
            i++;
        }
        
        row();

        option(I18n.t(session, "@Back", session.player), (s, st) -> {
            Registry.get(RareUpgradeBuffSelectUnitMenu.class).send(s, null);
        });
        option(I18n.t(session, "@Close", session.player), (s, st) -> {});
    }
}
