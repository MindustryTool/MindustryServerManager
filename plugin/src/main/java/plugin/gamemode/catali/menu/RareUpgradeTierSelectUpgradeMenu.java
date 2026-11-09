package plugin.gamemode.catali.menu;

import java.util.Objects;
import common.util.Pair;
import lombok.RequiredArgsConstructor;
import mindustry.gen.Unit;
import mindustry.ui.builder.UiBuilder;
import plugin.PluginEvents;
import plugin.annotations.Component;
import plugin.annotations.ConditionOn;
import plugin.gamemode.GamemodeCondition;
import plugin.gamemode.catali.CataliConfig;
import plugin.gamemode.catali.data.CataliTeamData;
import plugin.gamemode.catali.event.CataliTierRareUpgrade;
import plugin.menus.MenuContext;
import plugin.menus.ServerMenu;
import plugin.session.Session;
import plugin.utils.Tr;

@Component
@RequiredArgsConstructor
@ConditionOn(value = GamemodeCondition.class, args = {"catali"})
public class RareUpgradeTierSelectUpgradeMenu extends ServerMenu<Pair<CataliTeamData, Unit>> {

    private final CataliConfig config;

    @Override
    public void build(MenuContext<Pair<CataliTeamData, Unit>> ctx) {
        Session session = ctx.getSession();
        Pair<CataliTeamData, Unit> pair = ctx.getState();
        Objects.requireNonNull(session);
        Objects.requireNonNull(pair);

        CataliTeamData team = pair.first;
        Unit unit = pair.second;

        this.title = Tr.t(session, "catali.select_evolution");

        ctx.label("[lightgray]" + Tr.t(session, "catali.choose_evolution") + " " + Tr.t(session, "catali.for_unit") + " "
                + unit.type.emoji() + " [accent]" + unit.type.name + "[]").width(MenuContext.SAFE_WIDTH);
        ctx.row();

        var availableUnitsUpgradeTo = config.getUnitEvolutions(unit.type);

        ctx.pane(MenuContext.SAFE_WIDTH, 220f, paneTable -> {
            if (availableUnitsUpgradeTo.isEmpty()) {
                paneTable.add(UiBuilder.label(Tr.t(session, "catali.no_available_evolutions")).wrap().align("left").growX()).row();
                return;
            }

            for (var upgrade : availableUnitsUpgradeTo) {
                var card = UiBuilder.table().align("left");
                card.width(MenuContext.SAFE_CARD_WIDTH);

                card.add(UiBuilder.label(upgrade.emoji() + " [white]" + upgrade.name + "[]").wrap().align("left").growX().padLeft(4f));

                String actId = ctx.registerAction((sess, p, res) -> {
                    PluginEvents.fire(new CataliTierRareUpgrade(team, unit, upgrade));
                });

                card.add(UiBuilder.button("[green]" + Tr.t(session, "catali.evolve") + "[]")
                        .clicked(actId)
                        .width(95f)
                        .height(48f)
                        .align("right"));

                paneTable.add(card.growX().pad(2f)).row();
            }
        });
    }
}
