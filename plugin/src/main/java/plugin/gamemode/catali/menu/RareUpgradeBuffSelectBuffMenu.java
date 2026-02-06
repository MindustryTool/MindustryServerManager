package plugin.gamemode.catali.menu;

import dto.Pair;
import mindustry.gen.Unit;
import plugin.PluginEvents;
import plugin.annotations.Component;
import plugin.core.Registry;
import plugin.gamemode.catali.CataliConfig;
import plugin.gamemode.catali.data.CataliTeamData;
import plugin.gamemode.catali.event.CataliBuffRareUpgrade;
import plugin.menus.PluginMenu;
import plugin.service.I18n;
import plugin.type.Session;

@Component
public class RareUpgradeBuffSelectBuffMenu extends PluginMenu<Pair<CataliTeamData, Unit>> {

    @Override
    public void build(Session session, Pair<CataliTeamData, Unit> pair) {
        var team = pair.first;
        var unit = pair.second;

        var availableEffectsAppliedTo = CataliConfig.selectBuffsCanBeApplied(unit);

        title = I18n.t(session, "@Select Buff");
        description = I18n.t(session, "@Choose a buff to apply to the unit.");

        int i = 0;
        for (var effect : availableEffectsAppliedTo) {
            if (i > 0 && i % 2 == 0)
                row();

            option(effect.emoji() + " " + effect.name, (s, st) -> {
                PluginEvents.fire(new CataliBuffRareUpgrade(team, unit, effect));
            });
            i++;
        }

        row();

        option(I18n.t(session, "@Back"), (s, st) -> {
            Registry.get(RareUpgradeBuffSelectUnitMenu.class).send(s, team);
        });
        option(I18n.t(session, "@Close"), (s, st) -> {
        });
    }
}
